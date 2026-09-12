package com.moaje.banking.common.bank

import java.time.Instant

data class MockBankingAccountResponse(
    val providerAccountId: String,
    val accountNumber: String,
    val bankCode: String,
    val productName: String,
    val balance: Long,
    val status: String,
    val createdAt: Instant,
    val canceledAt: Instant?,
)

data class MockBankingAccountSnapshotResponse(
    val providerAccountId: String,
    val balance: Long,
    val status: String,
    val asOf: Instant,
    val histories: List<TransferHistoryResponse>,
)
