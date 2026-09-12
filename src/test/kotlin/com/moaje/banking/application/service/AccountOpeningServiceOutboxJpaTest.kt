package com.moaje.banking.application.service

import com.moaje.banking.application.event.BankingAccountEventPublisher
import com.moaje.banking.application.event.BankingKafkaTopicsProperties
import com.moaje.banking.application.service.impl.AccountOpeningServiceImpl
import com.moaje.banking.common.bank.MockBankingAccountResponse
import com.moaje.banking.common.restclient.MockBankingApiClient
import com.moaje.banking.domain.account.OpenAccountCommand
import com.moaje.banking.repository.account.BankingAccountRepository
import com.moaje.banking.repository.outbox.BankingOutboxEventRepository
import com.moaje.events.banking.AccountCreatedEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

@DataJpaTest
@DisplayName("Banking 계좌 연결과 AccountCreated Outbox")
class AccountOpeningServiceOutboxJpaTest(
    @Autowired private val accountRepository: BankingAccountRepository,
    @Autowired private val outboxRepository: BankingOutboxEventRepository,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val mockClient = mock(MockBankingApiClient::class.java)
    private val applicationEventPublisher = mock(ApplicationEventPublisher::class.java)
    private val service = AccountOpeningServiceImpl(
        mockClient,
        BankingAccountEventPublisher(outboxRepository, applicationEventPublisher, BankingKafkaTopicsProperties()),
        accountRepository,
        TransactionTemplate(transactionManager),
    )

    @Test
    @DisplayName("provider 계좌 연결과 생성 이벤트를 같은 DB 트랜잭션에 한 번만 저장한다")
    fun storesAccountMappingAndOutboxAtomicallyOnce() {
        // given
        val command = command("user-1")
        `when`(mockClient.openAccount(command)).thenReturn(providerAccount())

        // when: Mock이 멱등하게 같은 providerAccountId를 반환하는 재요청을 수행한다.
        val first = service.open(command)
        val second = service.open(command)

        // then: 같은 Banking accountId와 단일 AccountCreated Outbox만 존재한다.
        assertThat(second.accountId).isEqualTo(first.accountId)
        assertThat(accountRepository.count()).isEqualTo(1)
        assertThat(outboxRepository.count()).isEqualTo(1)
        val event = AccountCreatedEvent.parseFrom(outboxRepository.findAll().single().payload)
        assertThat(event.accountId).isEqualTo(first.accountId)
        assertThat(event.userId).isEqualTo("user-1")
    }

    private fun command(userId: String) = OpenAccountCommand(
        userId = userId,
        ci = "test-ci",
        userName = "테스트 사용자",
        phoneNumber = "01000000000",
        bankCode = "088",
        productName = "모아제 통장",
        initialBalance = 10_000L,
    )

    private fun providerAccount() = MockBankingAccountResponse(
        providerAccountId = "provider-opaque-1",
        accountNumber = "test-only-account-number",
        bankCode = "088",
        productName = "모아제 통장",
        balance = 10_000L,
        status = "ACTIVE",
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        canceledAt = null,
    )
}
