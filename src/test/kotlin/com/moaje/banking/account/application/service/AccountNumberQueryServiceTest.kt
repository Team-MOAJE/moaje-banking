package com.moaje.banking.account.application.service

import com.moaje.banking.account.domain.BankingAccount
import com.moaje.banking.account.persistence.BankingAccountRepository
import com.moaje.banking.integration.mockbanking.MockBankingApiClient
import com.moaje.banking.integration.mockbanking.model.MockBankingAccountResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.util.Optional

class AccountNumberQueryServiceTest {
    private val accounts = Mockito.mock(BankingAccountRepository::class.java)
    private val mock = Mockito.mock(MockBankingApiClient::class.java)
    private val service = AccountNumberQueryService(accounts, mock)

    private fun givenAccount() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(Optional.of(BankingAccount(
            accountId = 1L, principalId = "owner", providerAccountId = "provider", bankCode = "088", productName = "test",
        )))
    }

    @Test
    @DisplayName("소유권이 다르면 계정계를 호출하지 않는다")
    fun deniesWrongOwner() {
        // given
        givenAccount()
        // when / then
        assertThatThrownBy { service.getOwnedAccountNumber(1L, "other") }.isInstanceOf(SecurityException::class.java)
        Mockito.verifyNoInteractions(mock)
    }

    @Test
    @DisplayName("본인 계좌의 연결을 확인하고 14자리 번호만 반환한다")
    fun returnsOwnedNumber() {
        // given
        givenAccount()
        Mockito.`when`(mock.fetchProviderAccount("provider")).thenReturn(MockBankingAccountResponse(
            "provider", "12345678901234", "088", "test", 0, "ACTIVE", Instant.EPOCH, null,
        ))
        // when / then
        assertThat(service.getOwnedAccountNumber(1L, "owner")).isEqualTo("12345678901234")
    }
}
