package com.moaje.banking.transfer.domain

enum class BankingTransferStatus {
    REQUESTED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    REVERSED,
}
