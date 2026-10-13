package com.moaje.banking.transfer.application.model

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import io.swagger.v3.oas.annotations.media.Schema

// Transfer Request Result
data class TransferRequestResult(
    @get:JsonSerialize(using = ToStringSerializer::class)
    @get:Schema(type = "string", example = "123456789012345678")
    val transferId: Long,
    val publicTransferId: String,
    val status: TransferStatus,
    val message: String,
    val externalTransactionId: String? = null,
)
