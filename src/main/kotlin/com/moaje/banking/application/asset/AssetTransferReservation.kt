package com.moaje.banking.application.asset

data class AssetTransferReservation(
    val transferId: Long,
    val publicTransferId: String,
    val assetTransactionId: Long,
)
