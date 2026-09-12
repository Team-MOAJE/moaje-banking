package com.moaje.banking.application.service.impl

import com.moaje.banking.application.event.BankingAccountEventPublisher
import com.moaje.banking.application.service.AccountOpeningService
import com.moaje.banking.common.restclient.MockBankingApiClient
import com.moaje.banking.domain.account.OpenAccountCommand
import com.moaje.banking.domain.account.OpenAccountResult
import com.moaje.banking.domain.account.BankingAccount
import com.moaje.banking.repository.account.BankingAccountRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class AccountOpeningServiceImpl(
    private val mockBankingApiClient: MockBankingApiClient,
    private val bankingAccountEventPublisher: BankingAccountEventPublisher,
    private val bankingAccountRepository: BankingAccountRepository,
    private val transactionTemplate: TransactionTemplate,
) : AccountOpeningService {
    /**
     * 목업 뱅킹 서버에 계좌개설을 요청하고, 성공하면 계좌개설 완료 이벤트를 발행합니다.
     */
    override fun open(command: OpenAccountCommand): OpenAccountResult {
        // Mock Banking 응답을 기다리는 동안 Banking DB 트랜잭션과 Connection을 점유하지 않는다.
        val providerAccount = mockBankingApiClient.openAccount(command)
        bankingAccountRepository.findByProviderAccountId(providerAccount.providerAccountId)?.let {
            return it.toResult(command, providerAccount)
        }

        return try {
            transactionTemplate.execute {
                val linkedAccount = bankingAccountRepository.saveAndFlush(
                    BankingAccount(
                        principalId = command.userId,
                        providerAccountId = providerAccount.providerAccountId,
                        bankCode = providerAccount.bankCode,
                        productName = providerAccount.productName,
                    ),
                )
                val result = linkedAccount.toResult(command, providerAccount)
                bankingAccountEventPublisher.publishAccountCreated(result)
                result
            } ?: error("계좌 연결 저장 결과가 없습니다.")
        } catch (exception: DataIntegrityViolationException) {
            // 동시 재요청은 providerAccountId Unique Constraint가 최종 방어선이 되고, 패배한 요청은 기존 accountId를 반환한다.
            bankingAccountRepository.findByProviderAccountId(providerAccount.providerAccountId)
                ?.toResult(command, providerAccount)
                ?: throw exception
        }
    }

    private fun BankingAccount.toResult(
        command: OpenAccountCommand,
        providerAccount: com.moaje.banking.common.bank.MockBankingAccountResponse,
    ): OpenAccountResult {
        // providerAccountId가 같더라도 기존 Banking 계좌의 소유자가 다르면 재사용할 수 없다.
        // 이 검증은 계정계 응답이나 동시 요청이 다른 사용자의 accountId로 연결되는 수평 권한 상승을 막는다.
        require(principalId == command.userId) { "계정계 계좌의 기존 소유자와 요청 주체가 일치하지 않습니다." }

        return OpenAccountResult(
            accountId = accountId ?: error("Banking accountId가 없습니다."),
            userId = principalId,
            bankCode = bankCode,
            productName = productName,
            balance = providerAccount.balance,
            status = providerAccount.status,
            createdAt = providerAccount.createdAt,
            canceledAt = providerAccount.canceledAt,
        )
    }
}
