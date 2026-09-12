package com.moaje.banking.application.service

import com.moaje.banking.application.event.BankingTransferEventPublisher
import com.moaje.banking.application.event.BankingKafkaTopicsProperties
import com.moaje.banking.application.service.impl.TransferCommandServiceImpl
import com.moaje.banking.common.id.IdGenerator
import com.moaje.banking.common.restclient.MockBankingApiClient
import com.moaje.banking.common.restclient.MockBankingTransferResult
import com.moaje.banking.domain.common.Money
import com.moaje.banking.domain.account.BankingAccount
import com.moaje.banking.domain.account.BankingAccountNotActiveException
import com.moaje.banking.domain.account.BankingAccountOwnershipException
import com.moaje.banking.domain.account.BankingAccountStatus
import com.moaje.banking.domain.kftc.KftcApiLogStatus
import com.moaje.banking.domain.outbox.BankingOutboxStatus
import com.moaje.banking.domain.transfer.BankingTransferStatus
import com.moaje.banking.domain.transfer.IdempotencyKeyConflictException
import com.moaje.banking.domain.transfer.OperationType
import com.moaje.banking.domain.transfer.PrincipalId
import com.moaje.banking.domain.transfer.TransferCommand
import com.moaje.banking.domain.transfer.TransferRequestHash
import com.moaje.banking.domain.transfer.TransferStatus
import com.moaje.banking.repository.kftc.KftcApiLogRepository
import com.moaje.banking.repository.account.BankingAccountRepository
import com.moaje.banking.repository.outbox.BankingOutboxEventRepository
import com.moaje.banking.repository.transfer.BankingTransferRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import com.moaje.events.banking.BankingTransferCompletedEvent
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest
class TransferCommandServiceIdempotencyTest(
    @Autowired private val bankingTransferRepository: BankingTransferRepository,
    @Autowired private val kftcApiLogRepository: KftcApiLogRepository,
    @Autowired private val outboxEventRepository: BankingOutboxEventRepository,
    @Autowired private val transactionManager: PlatformTransactionManager,
    @Autowired private val eventPublisher: ApplicationEventPublisher,
) {
    private lateinit var mockBankingApiClient: MockBankingApiClient
    private lateinit var bankingTransferEventPublisher: BankingTransferEventPublisher
    private lateinit var idGenerator: IdGenerator
    private lateinit var service: TransferCommandService
    private lateinit var bankingAccountRepository: BankingAccountRepository
    private val withdrawalAccountId: Long = 1L

    @BeforeEach
    fun setUp() {
        deletePersistedRows()
        mockBankingApiClient = Mockito.mock(MockBankingApiClient::class.java)
        bankingTransferEventPublisher = BankingTransferEventPublisher(
            topics = BankingKafkaTopicsProperties(),
            outboxEventRepository = outboxEventRepository,
            eventPublisher = eventPublisher,
        )
        idGenerator = Mockito.mock(IdGenerator::class.java)
        bankingAccountRepository = Mockito.mock(BankingAccountRepository::class.java)
        Mockito.`when`(bankingAccountRepository.findById(withdrawalAccountId))
            .thenReturn(java.util.Optional.of(account()))
        service = TransferCommandServiceImpl(
            mockBankingApiClient = mockBankingApiClient,
            kftcApiLogRepository = kftcApiLogRepository,
            bankingAccountRepository = bankingAccountRepository,
            bankingTransferRepository = bankingTransferRepository,
            bankingTransferEventPublisher = bankingTransferEventPublisher,
            idGenerator = idGenerator,
            transactionTemplate = TransactionTemplate(transactionManager),
        )
    }

    @AfterEach
    fun tearDown() {
        deletePersistedRows()
    }

    private fun deletePersistedRows() {
        TransactionTemplate(transactionManager).executeWithoutResult {
            outboxEventRepository.deleteAll()
            kftcApiLogRepository.deleteAll()
            bankingTransferRepository.deleteAll()
            outboxEventRepository.flush()
            kftcApiLogRepository.flush()
            bankingTransferRepository.flush()
        }
    }

    @Test
    @DisplayName("동일 principalId, 동일 키, 동일 요청이면 같은 transferId를 반환하고 Mock Banking은 한 번만 호출한다")
    fun sameIdempotencyKeyAndSameRequestReturnsSameTransferIdWithoutSecondExternalCall() {
        // given: 동일한 멱등성 키로 같은 송금 의도가 두 번 들어온다.
        val command = command()
        val bankingResult = success("EXT-1")
        Mockito.`when`(idGenerator.nextId()).thenReturn(100L)
        Mockito.`when`(mockBankingApiClient.transferRequest(100L, command, "provider-opaque-1")).thenReturn(bankingResult)

        // when
        val first = service.request(command)
        val second = service.request(command)

        // then: 두 번째 요청은 기존 Aggregate 상태를 반환하고 외부 계정계를 다시 호출하지 않는다.
        assertThat(first.transferId).isEqualTo(100L)
        assertThat(second.transferId).isEqualTo(100L)
        assertThat(second.status).isEqualTo(TransferStatus.COMPLETED)
        Mockito.verify(mockBankingApiClient, Mockito.times(1)).transferRequest(100L, command, "provider-opaque-1")
        assertThat(bankingTransferRepository.count()).isEqualTo(1)
        assertThat(outboxEventRepository.count()).isEqualTo(1)
    }

    @Test
    @DisplayName("동일 사용자와 같은 키에 금액이 다르면 409로 변환할 멱등성 충돌 예외를 던지고 Mock Banking을 재호출하지 않는다")
    fun sameKeyWithDifferentAmountThrowsConflictBeforeExternalCall() {
        // given: 첫 요청은 성공했고, 같은 키로 금액만 바꾼 요청이 다시 들어온다.
        val command = command()
        val changed = command(amount = 20_000)
        val bankingResult = success("EXT-1")
        Mockito.`when`(idGenerator.nextId()).thenReturn(100L)
        Mockito.`when`(mockBankingApiClient.transferRequest(100L, command, "provider-opaque-1")).thenReturn(bankingResult)
        service.request(command)

        // when & then: requestHash가 다르므로 외부 계정계를 호출하기 전에 차단한다.
        assertThatThrownBy { service.request(changed) }
            .isInstanceOf(IdempotencyKeyConflictException::class.java)
        Mockito.verify(mockBankingApiClient, Mockito.times(1)).transferRequest(100L, command, "provider-opaque-1")
        assertThat(bankingTransferRepository.count()).isEqualTo(1)
        assertThat(outboxEventRepository.count()).isEqualTo(1)
    }

    @Test
    @DisplayName("동시에 동일 멱등성 요청이 들어와도 banking_transfer Row는 한 건만 생성된다")
    fun concurrentSameIdempotencyRequestCreatesOnlyOneTransferRow() {
        // given: 첫 번째 외부 호출을 잠시 붙잡아 두어 두 번째 요청이 처리 중 상태를 관찰하게 한다.
        val command = command()
        val externalCallStarted = CountDownLatch(1)
        val releaseExternalCall = CountDownLatch(1)
        Mockito.`when`(idGenerator.nextId()).thenReturn(100L, 101L)
        Mockito.`when`(mockBankingApiClient.transferRequest(100L, command, "provider-opaque-1")).thenAnswer {
            externalCallStarted.countDown()
            releaseExternalCall.await(3, TimeUnit.SECONDS)
            success("EXT-1")
        }

        val executor = Executors.newFixedThreadPool(2)
        val first = executor.submit(Callable { service.request(command) })
        externalCallStarted.await(3, TimeUnit.SECONDS)
        val second = executor.submit(Callable { service.request(command) })

        // when
        val secondResult = second.get(3, TimeUnit.SECONDS)
        releaseExternalCall.countDown()
        val firstResult = first.get(3, TimeUnit.SECONDS)
        executor.shutdown()

        // then: DB Unique Constraint와 조회 로직이 함께 중복 row 생성을 막는다.
        assertThat(firstResult.transferId).isEqualTo(100L)
        assertThat(secondResult.transferId).isEqualTo(100L)
        assertThat(bankingTransferRepository.count()).isEqualTo(1)
        assertThat(outboxEventRepository.count()).isEqualTo(1)
        Mockito.verify(mockBankingApiClient, Mockito.times(1)).transferRequest(100L, command, "provider-opaque-1")
    }

    @Test
    @DisplayName("Mock Banking에 전달한 clientTransferId는 Banking transferId와 동일하다")
    fun mockBankingClientTransferIdMatchesBankingTransferId() {
        // given
        val command = command()
        val bankingResult = success("EXT-1")
        Mockito.`when`(idGenerator.nextId()).thenReturn(100L)
        Mockito.`when`(mockBankingApiClient.transferRequest(100L, command, "provider-opaque-1")).thenReturn(bankingResult)

        // when
        val result = service.request(command)

        // then
        assertThat(result.transferId).isEqualTo(100L)
        Mockito.verify(mockBankingApiClient).transferRequest(100L, command, "provider-opaque-1")
    }

    @Test
    @DisplayName("Mock Banking 자체 거래 ID가 반환되면 externalTransactionId에 저장된다")
    fun storesExternalTransactionIdWhenMockBankingReturnsIt() {
        // given
        val command = command()
        val bankingResult = success("MOCK-HISTORY-1")
        Mockito.`when`(idGenerator.nextId()).thenReturn(100L)
        Mockito.`when`(mockBankingApiClient.transferRequest(100L, command, "provider-opaque-1")).thenReturn(bankingResult)

        // when
        service.request(command)

        // then
        val transfer = bankingTransferRepository.findById(100L).orElseThrow()
        assertThat(transfer.externalTransactionId).isEqualTo("MOCK-HISTORY-1")
        assertThat(transfer.status).isEqualTo(BankingTransferStatus.SUCCEEDED)
    }

    @Test
    @DisplayName("송금 성공 결과와 Asset 전파용 Outbox는 같은 DB 트랜잭션에 PENDING으로 저장된다")
    fun storesSuccessfulTransferResultAndPendingOutboxInOneDatabaseTransaction() {
        // given
        val command = command()
        val bankingResult = success("EXT-1")
        Mockito.`when`(idGenerator.nextId()).thenReturn(100L)
        Mockito.`when`(mockBankingApiClient.transferRequest(100L, command, "provider-opaque-1")).thenReturn(bankingResult)

        // when
        service.request(command)

        // then: Kafka 직접 발행 없이 DB에 남은 Outbox가 Producer 재발행의 기준점이 된다.
        val outbox = outboxEventRepository.findAll().single()
        assertThat(outbox.status).isEqualTo(BankingOutboxStatus.PENDING)
        assertThat(outbox.aggregateId).isEqualTo("100")
        assertThat(outbox.messageKey).isEqualTo(withdrawalAccountId.toString())
        assertThat(outbox.topic).isEqualTo("moaje.banking.transfer-completed")
        assertThat(outbox.eventType).isEqualTo("BankingTransferCompletedEvent")
        assertThat(outbox.attemptCount).isZero()
        val event = BankingTransferCompletedEvent.parseFrom(outbox.payload)
        assertThat(event.accountId).isEqualTo(withdrawalAccountId)
        assertThat(event.transferId).isEqualTo(100L)
        assertThat(event.externalTransactionId).isEqualTo("EXT-1")
    }

    @Test
    @DisplayName("허용되지 않는 거래 상태 전이는 차단된다")
    fun invalidStatusTransitionIsRejected() {
        // given
        val transfer = com.moaje.banking.domain.transfer.BankingTransfer.create(
            transferId = 100L,
            principalId = PrincipalId("user-1"),
            idempotencyKey = "idem-1",
            requestHash = TransferRequestHash.from(command()),
            command = command(),
        )

        // when & then: REQUESTED에서 곧바로 SUCCEEDED로 넘어갈 수 없다.
        assertThatThrownBy { transfer.markSucceeded("EXT-1") }
            .isInstanceOf(com.moaje.banking.domain.transfer.InvalidTransferStateTransitionException::class.java)
    }

    @Test
    @DisplayName("Timeout은 즉시 FAILED로 확정하지 않고 UNKNOWN과 대사 필요 상태로 기록된다")
    fun timeoutIsStoredAsUnknownAndReconciliationRequired() {
        // given
        val command = command()
        val bankingResult = MockBankingTransferResult(
            status = KftcApiLogStatus.TIMEOUT,
            requestPayload = "{}",
            responseCode = "TIMEOUT",
            failureReason = "timeout",
        )
        Mockito.`when`(idGenerator.nextId()).thenReturn(100L)
        Mockito.`when`(mockBankingApiClient.transferRequest(100L, command, "provider-opaque-1")).thenReturn(bankingResult)

        // when
        val result = service.request(command)

        // then
        val transfer = bankingTransferRepository.findById(100L).orElseThrow()
        assertThat(result.status).isEqualTo(TransferStatus.PROCESSING)
        assertThat(transfer.status).isEqualTo(BankingTransferStatus.UNKNOWN)
        assertThat(transfer.reconciliationRequired).isTrue()
        assertThat(outboxEventRepository.count()).isZero()
    }

    @Test
    @DisplayName("다른 사용자의 출금 계좌는 외부 호출과 거래 생성 전에 차단한다")
    fun rejectsWithdrawalAccountOwnedByAnotherPrincipal() {
        // given: accountId는 존재하지만 인증된 principalId와 계좌 소유자가 다르다.
        val command = command(principalId = "other-user")

        // when & then: 소유권 검증 실패는 Mock Banking 호출이나 거래 Journal을 남기지 않는다.
        assertThatThrownBy { service.request(command) }
            .isInstanceOf(BankingAccountOwnershipException::class.java)
        Mockito.verifyNoInteractions(mockBankingApiClient)
        assertThat(bankingTransferRepository.count()).isZero()
    }

    @Test
    @DisplayName("비활성 출금 계좌는 외부 호출과 거래 생성 전에 차단한다")
    fun rejectsInactiveWithdrawalAccount() {
        // given: Banking 계좌 연결 상태가 더 이상 금융 명령을 허용하지 않는다.
        Mockito.`when`(bankingAccountRepository.findById(withdrawalAccountId))
            .thenReturn(java.util.Optional.of(account(status = BankingAccountStatus.INACTIVE)))

        // when & then: 계정계 호출 전에 상태 정책이 적용되어 잘못된 송금 시도를 남기지 않는다.
        assertThatThrownBy { service.request(command()) }
            .isInstanceOf(BankingAccountNotActiveException::class.java)
        Mockito.verifyNoInteractions(mockBankingApiClient)
        assertThat(bankingTransferRepository.count()).isZero()
    }

    @Test
    @DisplayName("Request Hash는 개인정보가 아니라 송금 의미 필드로만 계산된다")
    fun requestHashUsesOnlyTransferMeaningFields() {
        // given: principalId가 달라도 송금 의미 필드가 같으면 requestHash는 같다.
        val first = command(principalId = "user-1")
        val second = command(principalId = "user-2")
        val changedAmount = command(amount = 20_000)

        // when
        val firstHash = TransferRequestHash.from(first)
        val secondHash = TransferRequestHash.from(second)
        val changedAmountHash = TransferRequestHash.from(changedAmount)

        // then
        assertThat(firstHash).isEqualTo(secondHash)
        assertThat(firstHash).isNotEqualTo(changedAmountHash)
    }

    private fun command(
        principalId: String = "user-1",
        idempotencyKey: String = "idem-1",
        amount: Long = 10_000,
        depositAccountNumber: String = "deposit-account",
    ): TransferCommand {
        return TransferCommand(
            principalId = PrincipalId(principalId),
            idempotencyKey = idempotencyKey,
            withdrawalAccountId = withdrawalAccountId,
            depositBankCode = "088",
            depositAccountNumber = depositAccountNumber,
            amount = Money(BigDecimal.valueOf(amount)),
        )
    }

    private fun success(externalTransactionId: String): MockBankingTransferResult {
        return MockBankingTransferResult(
            status = KftcApiLogStatus.SUCCESS,
            requestPayload = "{}",
            responseCode = "200",
            externalTransactionId = externalTransactionId,
        )
    }

    private fun account(status: BankingAccountStatus = BankingAccountStatus.ACTIVE) = BankingAccount(
        accountId = withdrawalAccountId,
        principalId = "user-1",
        providerAccountId = "provider-opaque-1",
        status = status,
        bankCode = "088",
        productName = "테스트 계좌",
    )
}
