package com.moaje.banking.common.bank

import java.time.Instant

data class TransferResponse(
    val clientTransferId: String? = null,
    val fromAccountNumber: String,
    val toAccountNumber: String,
    val amount: Long,
    val fromBalance: Long,
    val toBalance: Long,
    val debitHistory: TransferHistoryResponse,
    val creditHistory: TransferHistoryResponse,
)

data class TransferReversalResponse(
    val clientTransferId: String,
    val fromAccountNumber: String,
    val toAccountNumber: String,
    val amount: Long,
    val fromBalance: Long,
    val toBalance: Long,
    val alreadyReversed: Boolean,
    val debitReversalHistory: TransferHistoryResponse?,
    val creditReversalHistory: TransferHistoryResponse?,
)

data class TransferLookupResponse(
    val clientTransferId: String,
    val fromAccountNumber: String,
    val toAccountNumber: String,
    val amount: Long,
    val fromBalance: Long,
    val toBalance: Long,
    val debitTransactionId: String,
    val creditTransactionId: String,
    val status: String,
    val debitReversalTransactionId: String? = null,
    val creditReversalTransactionId: String? = null,
    val reversedAt: Instant? = null,
)

data class TransferHistoryResponse(
    val transactionId: String,
    val accountNumber: String,
    val type: String,
    val amount: Long,
    val counterpartyAccountNumber: String?,
    val counterpartyBankCode: String?,
    val memo: String?,
    val createdAt: Instant,
    val completedAtEpochMillis: Long? = null,
)
