package com.moaje.banking.application.service.impl

import com.moaje.banking.application.event.BankingAccountEventPublisher
import com.moaje.banking.application.service.AccountOpeningService
import com.moaje.banking.common.restclient.MockBankingApiClient
import com.moaje.banking.domain.account.OpenAccountCommand
import com.moaje.banking.domain.account.OpenAccountResult
import org.springframework.stereotype.Service

@Service
class AccountOpeningServiceImpl(
    private val mockBankingApiClient: MockBankingApiClient,
    private val bankingAccountEventPublisher: BankingAccountEventPublisher,
) : AccountOpeningService {
    /**
     * 목업 뱅킹 서버에 계좌개설을 요청하고, 성공하면 계좌개설 완료 이벤트를 발행합니다.
     */
    override fun open(command: OpenAccountCommand): OpenAccountResult {
        val account = mockBankingApiClient.openAccount(command)
        val result = OpenAccountResult(
            userId = command.userId,
            accountNumber = account.accountNumber,
            bankCode = account.bankCode,
            productName = account.productName,
            balance = account.balance,
            status = account.status,
            createdAt = account.createdAt,
            canceledAt = account.canceledAt,
        )

        // TODO: Auth 서비스 RPC 스펙 확정 후 accountNumber는 Auth에서 암호화 저장하고, Kafka에는 토큰/마스킹 값만 싣도록 변경합니다.
        bankingAccountEventPublisher.publishAccountCreated(result)
        return result
    }
}
