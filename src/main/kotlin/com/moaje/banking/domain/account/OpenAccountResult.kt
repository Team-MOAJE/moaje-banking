package com.moaje.banking.domain.account

import java.time.Instant

data class OpenAccountResult(
    val accountId: Long,
    val userId: String,
    val bankCode: String,
    val productName: String,
    val balance: Long,
    val status: String,
    val createdAt: Instant,
    val canceledAt: Instant?,
)
