package com.moaje.banking.domain.transfer

import com.moaje.banking.domain.common.Money
import java.time.Instant

data class TransferRequestedEvent(
    val eventId: String,
    val transferId: Long,
    val assetTransactionId: Long,
    val ci: String,
    val userName: String,
    val phoneNumber: String,
    val requesterUserId: String,
    val withdrawalAccountId: String,
    val depositBankCode: String,
    val depositAccountNumber: String,
    val amount: Money,
    val occurredAt: Instant,
)
