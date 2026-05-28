package com.moaje.banking.common.bank

import java.time.Instant

data class MockBankingAccountResponse(
    val accountNumber: String,
    val bankCode: String,
    val productName: String,
    val balance: Long,
    val status: String,
    val createdAt: Instant,
    val canceledAt: Instant?,
)
