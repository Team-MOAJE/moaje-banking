package com.moaje.banking.application.reconciliation

import com.moaje.banking.application.event.BankingKafkaTopicsProperties
import com.moaje.banking.application.event.BankingTransferEventPublisher
import com.moaje.banking.common.restclient.MockBankingApiClient
import com.moaje.banking.common.restclient.MockBankingTransferResult
import com.moaje.banking.domain.common.Money
import com.moaje.banking.domain.kftc.KftcApiLogStatus
import com.moaje.banking.domain.outbox.BankingOutboxStatus
import com.moaje.banking.domain.transfer.BankingTransfer
import com.moaje.banking.domain.transfer.BankingTransferStatus
import com.moaje.banking.domain.transfer.PrincipalId
import com.moaje.banking.domain.transfer.TransferCommand
import com.moaje.banking.domain.transfer.TransferRequestHash
import com.moaje.banking.repository.kftc.KftcApiLogRepository
import com.moaje.banking.repository.outbox.BankingOutboxEventRepository
import com.moaje.banking.repository.transfer.BankingTransferRepository
import com.moaje.events.banking.BankingTransferReversedEvent
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime

@DataJpaTest
class TransferReconciliationServiceTest(
    @Autowired private val bankingTransferRepository: BankingTransferRepository,
    @Autowired private val kftcApiLogRepository: KftcApiLogRepository,
    @Autowired private val outboxEventRepository: BankingOutboxEventRepository,
    @Autowired private val transactionManager: PlatformTransactionManager,
    @Autowired private val eventPublisher: ApplicationEventPublisher,
) {
    private lateinit var mockBankingApiClient: MockBankingApiClient
    private lateinit var service: TransferReconciliationService
    private lateinit var meterRegistry: SimpleMeterRegistry

    @BeforeEach
    fun setUp() {
        deletePersistedRows()
        mockBankingApiClient = Mockito.mock(MockBankingApiClient::class.java)
        val properties = TransferReconciliationProperties().apply {
            batchSize = 10
            processingStaleAfterMs = 60_000
            leaseDurationMs = 30_000
            maxAttempts = 2
            baseBackoffMs = 1_000
            maxBackoffMs = 10_000
        }
        meterRegistry = SimpleMeterRegistry()
        service = TransferReconciliationService(
            bankingTransferRepository = bankingTransferRepository,
            kftcApiLogRepository = kftcApiLogRepository,
            mockBankingApiClient = mockBankingApiClient,
            bankingTransferEventPublisher = BankingTransferEventPublisher(
                topics = BankingKafkaTopicsProperties(),
                outboxEventRepository = outboxEventRepository,
                eventPublisher = eventPublisher,
            ),
            properties = properties,
            metrics = TransferReconciliationMetrics(bankingTransferRepository, properties, meterRegistry),
            transactionTemplate = TransactionTemplate(transactionManager),
        )
    }

    @AfterEach
    fun tearDown() {
        deletePersistedRows()
    }

    @Test
    @DisplayName("UNKNOWN 거래는 Mock Banking 조회 성공 후 SUCCEEDED와 Outbox PENDING으로 복구된다")
    fun unknownTransferIsRecoveredAsSucceededWithPendingOutbox() {
        // given: Timeout으로 UNKNOWN에 머문 송금이 Mock Banking에는 성공으로 기록되어 있다.
        saveUnknownTransfer(100L)
        Mockito.`when`(mockBankingApiClient.lookupTransferResult(100L)).thenReturn(successLookup("EXT-100"))

        // when
        val result = service.reconcileBatch()

        // then: 원거래 성공이 확인된 뒤 Banking 상태와 Asset 전파 이벤트가 같은 DB 흐름으로 남는다.
        val transfer = bankingTransferRepository.findById(100L).orElseThrow()
        assertThat(result.scanned).isEqualTo(1)
        assertThat(result.resolved).isEqualTo(1)
        assertThat(transfer.status).isEqualTo(BankingTransferStatus.SUCCEEDED)
        assertThat(transfer.externalTransactionId).isEqualTo("EXT-100")
        assertThat(transfer.reconciliationRequired).isFalse()
        assertThat(transfer.reconciliationAttemptCount).isEqualTo(1)
        assertThat(transfer.lastReconciliationFailureReason).isNull()
        assertThat(transfer.reconciliationLockedUntil).isNull()
        assertThat(transfer.reconciliationLockOwner).isNull()
        assertThat(kftcApiLogRepository.count()).isEqualTo(1)
        val outbox = outboxEventRepository.findAll().single()
        assertThat(outbox.status).isEqualTo(BankingOutboxStatus.PENDING)
        assertThat(outbox.aggregateId).isEqualTo("100")
        assertThat(outbox.eventType).isEqualTo("BankingTransferCompletedEvent")
    }

    @Test
    @DisplayName("오래된 PROCESSING 거래가 Mock Banking에서 NOT_FOUND이면 FAILED로 확정한다")
    fun staleProcessingTransferIsRecoveredAsFailedWhenMockReturnsNotFound() {
        // given: 외부 호출 직후 서버가 중단되어 PROCESSING에 오래 머문 거래가 있다.
        saveProcessingTransfer(101L, LocalDateTime.now().minusMinutes(5))
        Mockito.`when`(mockBankingApiClient.lookupTransferResult(101L)).thenReturn(notFoundLookup())

        // when
        val result = service.reconcileBatch()

        // then: Mock Banking의 clientTransferId 강한 일관성 조회가 없음을 확정했으므로 실패로 기록한다.
        val transfer = bankingTransferRepository.findById(101L).orElseThrow()
        assertThat(result.scanned).isEqualTo(1)
        assertThat(result.resolved).isEqualTo(1)
        assertThat(transfer.status).isEqualTo(BankingTransferStatus.FAILED)
        assertThat(transfer.failureCode).isEqualTo("404")
        assertThat(transfer.reconciliationRequired).isFalse()
        assertThat(transfer.reconciliationLockedUntil).isNull()
        val outbox = outboxEventRepository.findAll().single()
        assertThat(outbox.status).isEqualTo(BankingOutboxStatus.PENDING)
        assertThat(outbox.eventType).isEqualTo("BankingTransferFailedEvent")
    }

    @Test
    @DisplayName("아직 stale 기준을 넘지 않은 PROCESSING 거래는 Reconciliation Worker가 가져가지 않는다")
    fun freshProcessingTransferIsNotReconciled() {
        // given: 다른 요청 스레드가 아직 Mock Banking 응답을 기다릴 수 있는 PROCESSING 거래가 있다.
        saveProcessingTransfer(102L, LocalDateTime.now())

        // when
        val result = service.reconcileBatch()

        // then: 진행 중일 가능성이 있는 거래는 조회하지 않아 중복 판단을 피한다.
        val transfer = bankingTransferRepository.findById(102L).orElseThrow()
        assertThat(result.scanned).isZero()
        assertThat(transfer.status).isEqualTo(BankingTransferStatus.PROCESSING)
        Mockito.verifyNoInteractions(mockBankingApiClient)
    }

    @Test
    @DisplayName("Mock Banking 조회 장애는 실패 확정이 아니라 UNKNOWN과 대사 필요 상태로 남긴다")
    fun lookupUnavailableKeepsTransferUnknownAndReconciliationRequired() {
        // given: PROCESSING은 오래됐지만 Mock Banking 조회 자체가 timeout으로 실패한다.
        saveProcessingTransfer(103L, LocalDateTime.now().minusMinutes(5))
        Mockito.`when`(mockBankingApiClient.lookupTransferResult(103L)).thenReturn(lookupTimeout())

        // when
        val result = service.reconcileBatch()

        // then: 조회 실패는 원거래 실패가 아니므로 FAILED로 확정하지 않는다.
        val transfer = bankingTransferRepository.findById(103L).orElseThrow()
        assertThat(result.scanned).isEqualTo(1)
        assertThat(result.stillUnknown).isEqualTo(1)
        assertThat(transfer.status).isEqualTo(BankingTransferStatus.UNKNOWN)
        assertThat(transfer.reconciliationRequired).isTrue()
        assertThat(transfer.reconciliationAttemptCount).isEqualTo(1)
        assertThat(transfer.lastReconciliationFailureReason).contains("lookup timeout")
        assertThat(transfer.nextReconciliationAt).isAfter(LocalDateTime.now())
        assertThat(transfer.reconciliationRetryExhausted).isFalse()
        assertThat(transfer.reconciliationLockedUntil).isNull()
        assertThat(outboxEventRepository.count()).isZero()
    }

    @Test
    @DisplayName("UNKNOWN 거래가 이미 REVERSED이면 원 성공과 보상 Outbox를 함께 생성한다")
    fun unknownTransferAlreadyReversedCreatesCompletedAndReversedOutbox() {
        // given: Banking은 Timeout으로 원 성공을 기록하지 못했지만 Mock Banking에서는 송금과 보상이 모두 끝났다.
        saveUnknownTransfer(108L)
        Mockito.`when`(mockBankingApiClient.lookupTransferResult(108L)).thenReturn(reversedLookup())

        // when
        val result = service.reconcileBatch()

        // then: Asset이 출금 없이 보상 입금만 받지 않도록 두 금융 효과를 같은 Banking 트랜잭션에서 만든다.
        val transfer = bankingTransferRepository.findById(108L).orElseThrow()
        assertThat(result.resolved).isEqualTo(1)
        assertThat(transfer.status).isEqualTo(BankingTransferStatus.REVERSED)
        assertThat(transfer.externalTransactionId).isEqualTo("EXT-108")
        assertThat(transfer.externalReversalTransactionId).isEqualTo("REV-108")
        assertThat(transfer.reversalReason).contains("이미 완료된")
        assertThat(transfer.reconciliationRequired).isFalse()
        assertThat(outboxEventRepository.findAll().map { it.eventType })
            .containsExactlyInAnyOrder("BankingTransferCompletedEvent", "BankingTransferReversedEvent")
        val reversedOutbox = outboxEventRepository.findAll().single { it.eventType == "BankingTransferReversedEvent" }
        val reversedEvent = BankingTransferReversedEvent.parseFrom(reversedOutbox.payload)
        assertThat(reversedEvent.transferId).isEqualTo(108L)
        assertThat(reversedEvent.accountId).isEqualTo(108L)
        assertThat(reversedEvent.originalExternalTransactionId).isEqualTo("EXT-108")
        assertThat(reversedEvent.externalReversalTransactionId).isEqualTo("REV-108")
        assertThat(meterRegistry.find("banking.reconciliation.transfer.reversed").counter()!!.count())
            .isEqualTo(1.0)
    }

    @Test
    @DisplayName("다른 Worker의 lease가 살아 있는 거래는 Mock Banking 조회 대상에서 제외된다")
    fun lockedTransferIsSkippedUntilLeaseExpires() {
        // given: 다른 인스턴스가 이미 대사 lease를 잡았다면 같은 거래를 동시에 조회하면 안 된다.
        saveUnknownTransfer(104L)
        TransactionTemplate(transactionManager).executeWithoutResult {
            val transfer = bankingTransferRepository.findByIdForUpdate(104L)!!
            transfer.reconciliationLockedAt = LocalDateTime.now()
            transfer.reconciliationLockedUntil = LocalDateTime.now().plusMinutes(1)
            transfer.reconciliationLockOwner = "other-worker"
        }

        // when
        val result = service.reconcileBatch()

        // then: lease가 살아 있는 동안에는 Mock Banking 조회 자체를 수행하지 않는다.
        assertThat(result.scanned).isZero()
        Mockito.verifyNoInteractions(mockBankingApiClient)
    }

    @Test
    @DisplayName("lease가 만료된 거래는 다른 Worker가 다시 선점해 복구할 수 있다")
    fun expiredLeaseAllowsAnotherWorkerToRecoverTransfer() {
        // given: 이전 Worker가 죽어 lease가 만료된 UNKNOWN 거래가 있다.
        saveUnknownTransfer(105L)
        TransactionTemplate(transactionManager).executeWithoutResult {
            val transfer = bankingTransferRepository.findByIdForUpdate(105L)!!
            transfer.reconciliationLockedAt = LocalDateTime.now().minusMinutes(2)
            transfer.reconciliationLockedUntil = LocalDateTime.now().minusMinutes(1)
            transfer.reconciliationLockOwner = "dead-worker"
        }
        Mockito.`when`(mockBankingApiClient.lookupTransferResult(105L)).thenReturn(successLookup("EXT-105"))

        // when
        val result = service.reconcileBatch()

        // then: 만료된 lease는 복구 가능해야 하므로 새 Worker가 성공 상태로 확정한다.
        val transfer = bankingTransferRepository.findById(105L).orElseThrow()
        assertThat(result.resolved).isEqualTo(1)
        assertThat(transfer.status).isEqualTo(BankingTransferStatus.SUCCEEDED)
        assertThat(transfer.externalTransactionId).isEqualTo("EXT-105")
    }

    @Test
    @DisplayName("조회 실패가 최대 재시도 횟수에 도달하면 자동 대사 대상에서 제외된다")
    fun lookupFailureMovesTransferToRetryExhaustedWhenMaxAttemptsReached() {
        // given: 이미 한 번 조회에 실패한 거래가 다시 조회 장애를 만난다.
        saveUnknownTransfer(106L)
        TransactionTemplate(transactionManager).executeWithoutResult {
            val transfer = bankingTransferRepository.findByIdForUpdate(106L)!!
            transfer.reconciliationAttemptCount = 1
            transfer.nextReconciliationAt = LocalDateTime.now().minusSeconds(1)
        }
        Mockito.`when`(mockBankingApiClient.lookupTransferResult(106L)).thenReturn(lookupTimeout())

        // when
        val result = service.reconcileBatch()

        // then: 무한 조회를 막기 위해 retry exhausted로 남기고 운영 확인 대상으로 보존한다.
        val transfer = bankingTransferRepository.findById(106L).orElseThrow()
        assertThat(result.stillUnknown).isEqualTo(1)
        assertThat(transfer.status).isEqualTo(BankingTransferStatus.UNKNOWN)
        assertThat(transfer.reconciliationRequired).isTrue()
        assertThat(transfer.reconciliationAttemptCount).isEqualTo(2)
        assertThat(transfer.reconciliationRetryExhausted).isTrue()
        assertThat(transfer.nextReconciliationAt).isNull()
        assertThat(meterRegistry.find("banking.reconciliation.transfer.retry.exhausted.total").counter()!!.count())
            .isEqualTo(1.0)
    }

    @Test
    @DisplayName("Reconciliation metric은 선점과 복구 결과를 낮은 cardinality로 기록한다")
    fun recordsReconciliationMetricsWithoutTransferIdTags() {
        // given
        saveUnknownTransfer(107L)
        Mockito.`when`(mockBankingApiClient.lookupTransferResult(107L)).thenReturn(successLookup("EXT-107"))

        // when
        service.reconcileBatch()

        // then: 개별 transferId를 tag로 남기지 않고 총량 counter/gauge만 확인한다.
        assertThat(meterRegistry.find("banking.reconciliation.transfer.claimed").counter()!!.count())
            .isEqualTo(1.0)
        assertThat(meterRegistry.find("banking.reconciliation.transfer.resolved").counter()!!.count())
            .isEqualTo(1.0)
        assertThat(meterRegistry.find("banking.reconciliation.transfer.retry.exhausted").gauge()!!.value())
            .isZero()
    }

    private fun saveUnknownTransfer(transferId: Long) {
        val transfer = newTransfer(transferId)
        transfer.markProcessing()
        transfer.markUnknown("TIMEOUT", "transfer timeout")
        bankingTransferRepository.saveAndFlush(transfer)
    }

    private fun saveProcessingTransfer(
        transferId: Long,
        processingStartedAt: LocalDateTime,
    ) {
        val transfer = newTransfer(transferId)
        transfer.markProcessing()
        transfer.processingStartedAt = processingStartedAt
        bankingTransferRepository.saveAndFlush(transfer)
    }

    private fun newTransfer(transferId: Long): BankingTransfer {
        val command = command(transferId)
        return BankingTransfer.create(
            transferId = transferId,
            principalId = command.principalId,
            idempotencyKey = command.idempotencyKey,
            requestHash = TransferRequestHash.from(command),
            command = command,
        )
    }

    private fun command(transferId: Long): TransferCommand {
        return TransferCommand(
            principalId = PrincipalId("user-$transferId"),
            idempotencyKey = "idem-$transferId",
            withdrawalAccountId = transferId,
            depositBankCode = "088",
            depositAccountNumber = "deposit-$transferId",
            amount = Money(BigDecimal.valueOf(10_000)),
        )
    }

    private fun successLookup(externalTransactionId: String): MockBankingTransferResult {
        return MockBankingTransferResult(
            status = KftcApiLogStatus.SUCCESS,
            requestPayload = """{"clientTransferId":"lookup"}""",
            responseCode = "200",
            externalTransactionId = externalTransactionId,
        )
    }

    private fun notFoundLookup(): MockBankingTransferResult {
        return MockBankingTransferResult(
            status = KftcApiLogStatus.FAILED,
            requestPayload = """{"clientTransferId":"lookup"}""",
            responseCode = "404",
            failureReason = "Mock Banking에 clientTransferId에 해당하는 송금 결과가 없습니다.",
        )
    }

    private fun lookupTimeout(): MockBankingTransferResult {
        return MockBankingTransferResult(
            status = KftcApiLogStatus.TIMEOUT,
            requestPayload = """{"clientTransferId":"lookup"}""",
            responseCode = "LOOKUP_TIMEOUT",
            failureReason = "lookup timeout",
        )
    }

    private fun reversedLookup(): MockBankingTransferResult {
        return MockBankingTransferResult(
            status = KftcApiLogStatus.REVERSED,
            requestPayload = """{"clientTransferId":"108"}""",
            responseCode = "200",
            externalTransactionId = "EXT-108",
            externalReversalTransactionId = "REV-108",
            reversedAt = Instant.parse("2026-09-07T00:00:00Z"),
            reversalReason = "Mock Banking 대사에서 이미 완료된 보상 거래를 확인했습니다.",
        )
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
}
