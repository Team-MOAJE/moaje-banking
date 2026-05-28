package com.moaje.banking.application.service

import com.moaje.banking.domain.account.OpenAccountCommand
import com.moaje.banking.domain.account.OpenAccountResult

interface AccountOpeningService {
    fun open(command: OpenAccountCommand): OpenAccountResult
}
