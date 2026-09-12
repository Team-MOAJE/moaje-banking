package com.moaje.banking.domain.transfer

enum class BankingTransferStatus {
    REQUESTED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    REVERSED,
}
