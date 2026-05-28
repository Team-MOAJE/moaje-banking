package com.moaje.banking.common.bank

import java.time.Instant

data class TransferResponse(
    val fromAccountNumber: String,
    val toAccountNumber: String,
    val amount: Long,
    val fromBalance: Long,
    val toBalance: Long,
    val debitHistory: TransferHistoryResponse,
    val creditHistory: TransferHistoryResponse,
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
)