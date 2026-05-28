package com.moaje.banking.application.service

import com.moaje.banking.domain.transfer.TransferCommand
import com.moaje.banking.domain.transfer.TransferRequestResult

interface TransferCommandService {
    fun request(command: TransferCommand): TransferRequestResult
}
