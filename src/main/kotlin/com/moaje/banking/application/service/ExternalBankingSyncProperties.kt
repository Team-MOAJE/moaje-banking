package com.moaje.banking.application.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Instant

@ConfigurationProperties(prefix = "moaje.banking.external-sync")
data class ExternalBankingSyncProperties(
    var enabled: Boolean = false,
    var fixedDelayMs: Long = 60_000,
    var cursor: Instant = Instant.EPOCH,
    var accounts: MutableList<ExternalBankingSyncAccountProperties> = mutableListOf(),
)

data class ExternalBankingSyncAccountProperties(
    var userId: Long = 0,
    var assetAccountId: Long = 0,
    var accountToken: String = "",
    var externalAccountNumber: String = "",
    var ci: String = "",
    var userName: String = "",
    var phoneNumber: String = "",
)
