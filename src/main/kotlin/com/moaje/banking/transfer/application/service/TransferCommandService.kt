package com.moaje.banking.transfer.application.service

import com.moaje.banking.transfer.domain.TransferCommand
import com.moaje.banking.transfer.application.model.TransferRequestResult

interface TransferCommandService {
    fun request(command: TransferCommand): TransferRequestResult
}
