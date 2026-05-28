package com.moaje.banking.application.asset

import java.math.BigDecimal
import java.time.Instant

data class ExternalAssetTransaction(
    val userId: Long,
    val accountId: Long,
    val accountToken: String,
    val externalTransactionId: String,
    val type: String,
    val amount: BigDecimal,
    val targetToken: String?,
    val occurredAt: Instant,
)
