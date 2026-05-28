package com.moaje.banking.application.service

import java.time.Instant

interface ExternalBankingSyncService {
    fun syncAfter(cursor: Instant)

    fun syncAccount(command: ExternalBankingSyncCommand): ExternalBankingSyncResult
}
