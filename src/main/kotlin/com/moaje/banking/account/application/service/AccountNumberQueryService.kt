package com.moaje.banking.account.application.service

import com.moaje.banking.account.persistence.BankingAccountRepository
import com.moaje.banking.integration.mockbanking.MockBankingApiClient
import org.springframework.stereotype.Service

@Service
class AccountNumberQueryService(
    private val accounts: BankingAccountRepository,
    private val mockBanking: MockBankingApiClient,
) {
    /**
     * Asset의 확인만 믿지 않고 Banking의 소유자도 다시 비교한다.
     * 원번호는 계정계에서 필요할 때만 읽고 DB·스냅샷·이벤트에 복제하지 않는다.
     */
    fun getOwnedAccountNumber(accountId: Long, principalId: String): String {
        require(accountId > 0 && principalId.isNotBlank()) { "계좌 ID와 요청 주체는 필수입니다." }
        val account = accounts.findById(accountId).orElseThrow { IllegalArgumentException("계좌를 찾을 수 없습니다.") }
        if (account.principalId != principalId) throw SecurityException("계좌 소유자가 아닙니다.")
        // Repository의 짧은 조회가 끝난 뒤 원격 호출한다. 이 메서드에 긴 DB 트랜잭션을 열지 않는다.
        val external = mockBanking.fetchProviderAccount(account.providerAccountId)
        check(external.providerAccountId == account.providerAccountId) { "계정계 계좌 연결이 일치하지 않습니다." }
        check(external.accountNumber.matches(Regex("[0-9]{14}"))) { "계정계 계좌번호 규격이 올바르지 않습니다." }
        return external.accountNumber
    }
}
