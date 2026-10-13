package com.moaje.banking.integration.mockbanking.model

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
    val bankName: String? = null,
)

data class MockBankingAccountSnapshotResponse(
    val providerAccountId: String,
    val balance: Long,
    val status: String,
    val asOf: Instant,
    val histories: List<TransferHistoryResponse>,
    val bankCode: String? = null,
    val bankName: String? = null,
)
