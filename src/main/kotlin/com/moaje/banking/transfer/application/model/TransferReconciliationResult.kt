package com.moaje.banking.transfer.application.model

data class TransferReconciliationResult(
    val scanned: Int,
    val resolved: Int,
    val failed: Int,
    val stillUnknown: Int,
)
