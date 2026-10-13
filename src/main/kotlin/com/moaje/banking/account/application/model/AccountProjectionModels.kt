package com.moaje.banking.account.application.model

import java.math.BigDecimal
import java.time.Instant

data class AccountProjectionSnapshotResult(
    val accountId: Long,
    val principalId: String,
    val balance: BigDecimal,
    val currency: String,
    val accountStatus: String,
    val asOf: Instant,
    val transactions: List<ExternalProjectionTransactionResult>,
    val bankCode: String? = null,
    val bankName: String? = null,
)

data class ExternalProjectionTransactionResult(
    val externalTransactionId: String,
    val type: String,
    val amount: BigDecimal,
    val currency: String,
    val occurredAt: Instant,
    val completedAt: Instant? = null,
    val merchantName: String? = null,
)
