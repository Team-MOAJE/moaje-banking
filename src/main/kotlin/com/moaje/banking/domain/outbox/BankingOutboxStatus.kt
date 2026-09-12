package com.moaje.banking.domain.outbox

enum class BankingOutboxStatus {
    PENDING,
    PUBLISHED,
    RETRY_EXHAUSTED,
}
