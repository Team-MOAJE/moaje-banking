package com.moaje.banking.domain.transfer

import com.moaje.banking.domain.common.Money
import java.time.Instant
import java.util.UUID

data class TransferCommand(
    val idempotencyKey: String = UUID.randomUUID().toString(),
    val ci: String,
    val userName: String,
    val phoneNumber: String,
    val requesterUserId: String,
    val withdrawalAccountId: String,
    val depositBankCode: String,
    val depositAccountNumber: String,
    val amount: Money,
    val requestedAt: Instant = Instant.now(),
)
