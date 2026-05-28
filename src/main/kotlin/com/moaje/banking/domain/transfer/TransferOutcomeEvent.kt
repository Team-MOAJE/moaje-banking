package com.moaje.banking.domain.transfer

import java.time.Instant

sealed interface TransferOutcomeEvent {
    val eventId: String
    val transferId: Long
    val assetTransactionId: Long
    val occurredAt: Instant
}

data class TransferCompletedEvent(
    override val eventId: String,
    override val transferId: Long,
    override val assetTransactionId: Long,
    val externalTransactionId: String,
    override val occurredAt: Instant = Instant.now(),
) : TransferOutcomeEvent

data class TransferFailedEvent(
    override val eventId: String,
    override val transferId: Long,
    override val assetTransactionId: Long,
    val reason: String,
    override val occurredAt: Instant = Instant.now(),
) : TransferOutcomeEvent
