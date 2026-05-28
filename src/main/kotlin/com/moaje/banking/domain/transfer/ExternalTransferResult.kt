package com.moaje.banking.domain.transfer

data class ExternalTransferResult(
    val succeeded: Boolean,
    val externalTransactionId: String?,
    val failureReason: String? = null,
) {
    companion object {
        fun success(externalTransactionId: String): ExternalTransferResult =
            ExternalTransferResult(succeeded = true, externalTransactionId = externalTransactionId)

        fun failure(reason: String): ExternalTransferResult =
            ExternalTransferResult(succeeded = false, externalTransactionId = null, failureReason = reason)
    }
}
