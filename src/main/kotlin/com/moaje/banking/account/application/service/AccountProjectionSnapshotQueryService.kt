package com.moaje.banking.account.application.service

import com.moaje.banking.account.application.model.AccountProjectionSnapshotResult
import com.moaje.banking.account.application.model.ExternalProjectionTransactionResult
import com.moaje.banking.integration.mockbanking.MockBankingApiClient
import com.moaje.banking.account.domain.BankingAccountStatus
import com.moaje.banking.account.persistence.BankingAccountRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant

@Service
class AccountProjectionSnapshotQueryService(
    private val accountRepository: BankingAccountRepository,
    private val mockBankingApiClient: MockBankingApiClient,
) {
    /**
     * Banking DB에서는 accountId와 providerAccountId의 연결만 읽고 곧바로 조회를 끝낸다.
     * 이후 Mock Banking 호출은 DB 트랜잭션 밖에서 실행해 느린 네트워크 동안 Connection을 점유하지 않는다.
     */
    fun getSnapshot(accountId: Long, cursor: Instant): AccountProjectionSnapshotResult {
        val account = accountRepository.findById(accountId).orElseThrow {
            IllegalArgumentException("Banking 계좌를 찾을 수 없습니다. accountId=$accountId")
        }
        require(account.status == BankingAccountStatus.ACTIVE) { "활성 Banking 계좌만 Snapshot을 조회할 수 있습니다." }

        val snapshot = mockBankingApiClient.fetchAccountSnapshot(account.providerAccountId, cursor)
        require(snapshot.providerAccountId == account.providerAccountId) {
            "Mock Banking Snapshot의 providerAccountId가 요청한 계좌와 다릅니다."
        }

        // 계좌번호와 상대 계좌번호는 Asset Projection에 필요하지 않으므로 gRPC 경계 밖으로 내보내지 않는다.
        return AccountProjectionSnapshotResult(
            accountId = accountId,
            bankCode = snapshot.bankCode?.takeIf(String::isNotBlank) ?: account.bankCode,
            bankName = snapshot.bankName?.takeIf(String::isNotBlank) ?: account.bankName,
            maskedAccountNumber = snapshot.maskedAccountNumber,
            principalId = account.principalId,
            balance = BigDecimal.valueOf(snapshot.balance),
            currency = "KRW",
            accountStatus = snapshot.status,
            asOf = snapshot.asOf,
            transactions = snapshot.histories.map { history ->
                ExternalProjectionTransactionResult(
                    externalTransactionId = history.transactionId,
                    type = history.type,
                    amount = BigDecimal.valueOf(history.amount),
                    currency = "KRW",
                    occurredAt = history.createdAt,
                    completedAt = history.completedAtEpochMillis?.let(Instant::ofEpochMilli),
                    merchantName = history.merchantName,
                )
            },
        )
    }
}
