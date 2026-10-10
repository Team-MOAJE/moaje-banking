package com.moaje.banking.transfer.application.model

import java.math.BigDecimal
import com.moaje.banking.transfer.domain.BankingTransferStatus

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
