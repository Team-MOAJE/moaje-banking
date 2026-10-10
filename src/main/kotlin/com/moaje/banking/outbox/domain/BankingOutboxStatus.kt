package com.moaje.banking.outbox.domain

enum class BankingOutboxStatus {
    PENDING,
    PUBLISHED,
    RETRY_EXHAUSTED,
}
