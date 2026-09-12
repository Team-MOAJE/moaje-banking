package com.moaje.banking.application.service

import com.moaje.banking.domain.transfer.BankingTransferStatus
import com.moaje.banking.repository.transfer.BankingTransferRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

data class TransferProjectionStateResult(
    val transferId: Long,
    val principalId: String,
    val accountId: Long,
    val amount: BigDecimal,
    val currency: String,
    val status: BankingTransferStatus,
    val externalTransactionId: String?,
    val externalReversalTransactionId: String?,
    val failureCode: String?,
    val failureReason: String?,
    val reversalReason: String?,
)

@Service
class TransferProjectionQueryService(
    private val bankingTransferRepository: BankingTransferRepository,
) {
    /**
     * Asset이 복구 대상으로 이미 식별한 거래만 Banking Journal에서 조회한다.
     * accountId 조건을 함께 검증해야 다른 계좌의 transferId를 추측해 조회하는 수평 권한 문제를 막을 수 있다.
     */
    @Transactional(readOnly = true)
    fun getStates(accountId: Long, transferIds: Collection<Long>): List<TransferProjectionStateResult> {
        require(accountId > 0) { "accountId는 0보다 커야 합니다." }
        require(transferIds.isNotEmpty()) { "대사할 transferId가 필요합니다." }

        val requestedIds = transferIds.distinct()
        val transfersById = bankingTransferRepository.findAllById(requestedIds).associateBy { it.transferId }
        val missingIds = requestedIds.filterNot(transfersById::containsKey)
        require(missingIds.isEmpty()) { "Banking 거래를 찾을 수 없습니다. transferIds=$missingIds" }

        return requestedIds.map { transferId ->
            val transfer = transfersById.getValue(transferId)
            require(transfer.withdrawalAccountId == accountId) {
                "요청한 계좌와 Banking 거래의 출금 계좌가 일치하지 않습니다. transferId=$transferId"
            }
            TransferProjectionStateResult(
                transferId = transfer.transferId,
                principalId = transfer.principalId,
                accountId = transfer.withdrawalAccountId,
                amount = transfer.amount,
                currency = transfer.currency,
                status = transfer.status,
                externalTransactionId = transfer.externalTransactionId,
                externalReversalTransactionId = transfer.externalReversalTransactionId,
                failureCode = transfer.failureCode,
                failureReason = transfer.failureReason,
                reversalReason = transfer.reversalReason,
            )
        }
    }
}
