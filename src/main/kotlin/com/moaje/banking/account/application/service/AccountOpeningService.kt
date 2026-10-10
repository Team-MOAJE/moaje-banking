package com.moaje.banking.account.application.service

import com.moaje.banking.account.application.model.OpenAccountCommand
import com.moaje.banking.account.application.model.OpenAccountResult

interface AccountOpeningService {
    fun open(command: OpenAccountCommand): OpenAccountResult
}
