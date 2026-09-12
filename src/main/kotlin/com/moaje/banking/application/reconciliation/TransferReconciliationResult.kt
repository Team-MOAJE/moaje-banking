package com.moaje.banking.application.reconciliation

data class TransferReconciliationResult(
    val scanned: Int,
    val resolved: Int,
    val failed: Int,
    val stillUnknown: Int,
)
