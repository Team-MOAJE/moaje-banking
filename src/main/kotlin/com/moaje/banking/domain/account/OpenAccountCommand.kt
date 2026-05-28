package com.moaje.banking.domain.account

data class OpenAccountCommand(
    val userId: String,
    val ci: String,
    val userName: String,
    val phoneNumber: String,
    val bankCode: String,
    val productName: String,
    val initialBalance: Long,
)
