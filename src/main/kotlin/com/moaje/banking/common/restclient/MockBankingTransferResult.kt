package com.moaje.banking.common.restclient

import com.moaje.banking.domain.kftc.KftcApiLogStatus
import java.time.Instant

data class MockBankingTransferResult(
    val status: KftcApiLogStatus,
    val requestPayload: String,
    val responseCode: String? = null,
    val externalTransactionId: String? = null,
    val externalReversalTransactionId: String? = null,
    val reversedAt: Instant? = null,
    val reversalReason: String? = null,
    val failureReason: String? = null,
) {
    val succeeded: Boolean
        get() = status == KftcApiLogStatus.SUCCESS && externalTransactionId != null
}
