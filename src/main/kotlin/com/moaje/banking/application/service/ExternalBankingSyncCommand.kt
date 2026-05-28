package com.moaje.banking.application.service

import java.time.Instant

data class ExternalBankingSyncCommand(
    val userId: Long,
    val assetAccountId: Long,
    val accountToken: String,
    val externalAccountNumber: String,
    val ci: String,
    val userName: String,
    val phoneNumber: String,
    val cursor: Instant,
)

data class ExternalBankingSyncResult(
    val syncedCount: Int,
)
