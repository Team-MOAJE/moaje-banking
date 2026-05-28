package com.moaje.banking.domain.transfer

data class TransferRequestResult(
    val transferId: Long,
    val publicTransferId: String,
    val status: TransferStatus,
    val message: String,
    val externalTransactionId: String? = null,
)
