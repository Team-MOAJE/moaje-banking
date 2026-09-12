package com.moaje.banking.application.service

import com.moaje.banking.common.bank.MockBankingAccountSnapshotResponse
import com.moaje.banking.common.bank.TransferHistoryResponse
import com.moaje.banking.common.restclient.MockBankingApiClient
import com.moaje.banking.domain.account.BankingAccount
import com.moaje.banking.repository.account.BankingAccountRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.Optional

@DisplayName("계정계 Account Snapshot 조회 경계")
class AccountProjectionSnapshotQueryServiceTest {
    private val accountRepository = mock(BankingAccountRepository::class.java)
    private val mockBankingApiClient = mock(MockBankingApiClient::class.java)
    private val service = AccountProjectionSnapshotQueryService(accountRepository, mockBankingApiClient)

    @Test
    @DisplayName("Asset accountId를 Banking 내부 providerAccountId로 변환하고 계좌번호 없는 결과를 반환한다")
    fun resolvesProviderAccountIdInsideBanking() {
        // given: Asset은 내부 accountId만 알고 Banking만 계정계 provider 식별자를 보유한다.
        val account = BankingAccount(
            accountId = 101L,
            principalId = "user-1",
            providerAccountId = "provider-opaque-1",
            bankCode = "088",
            productName = "모아제 통장",
        )
        val cursor = Instant.parse("2026-01-01T00:00:00Z")
        `when`(accountRepository.findById(101L)).thenReturn(Optional.of(account))
        `when`(mockBankingApiClient.fetchAccountSnapshot("provider-opaque-1", cursor)).thenReturn(
            MockBankingAccountSnapshotResponse(
                providerAccountId = "provider-opaque-1",
                balance = 12_000L,
                status = "ACTIVE",
                asOf = Instant.parse("2026-01-01T00:01:00Z"),
                histories = listOf(
                    TransferHistoryResponse(
                        transactionId = "EXT-1",
                        accountNumber = "plain-number-must-not-escape",
                        type = "DEPOSIT",
                        amount = 2_000L,
                        counterpartyAccountNumber = "counterparty-must-not-escape",
                        counterpartyBankCode = "088",
                        memo = null,
                        createdAt = Instant.parse("2026-01-01T00:00:30Z"),
                        completedAtEpochMillis = Instant.parse("2026-01-01T00:00:32Z").toEpochMilli(),
                    ),
                ),
            ),
        )

        // when
        val result = service.getSnapshot(101L, cursor)

        // then: Application 결과 타입에는 계좌번호 필드 자체가 없고 필요한 거래 식별자와 금액만 남는다.
        verify(mockBankingApiClient).fetchAccountSnapshot("provider-opaque-1", cursor)
        assertThat(result.accountId).isEqualTo(101L)
        assertThat(result.principalId).isEqualTo("user-1")
        assertThat(result.balance).isEqualByComparingTo("12000")
        assertThat(result.transactions.single().externalTransactionId).isEqualTo("EXT-1")
        assertThat(result.transactions.single().occurredAt).isEqualTo(Instant.parse("2026-01-01T00:00:30Z"))
        assertThat(result.transactions.single().completedAt).isEqualTo(Instant.parse("2026-01-01T00:00:32Z"))
    }
}
