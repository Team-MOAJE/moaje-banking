package com.moaje.banking.application.service

import com.moaje.banking.domain.transfer.TransferRequestedEvent

interface TransferEventService {
    fun process(event: TransferRequestedEvent)
}
