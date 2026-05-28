package com.moaje.banking.infra.kafka

import com.moaje.banking.application.service.TransferEventService
import com.moaje.banking.domain.common.Money
import com.moaje.banking.domain.transfer.TransferRequestedEvent
import com.moaje.events.asset.AssetTransferRequestedEvent
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

@Component
class AssetTransferRequestedEventConsumer(
    private val transferEventService: TransferEventService,
) {
    /**
     * Asset 서비스의 송금 요청 Kafka 메시지를 수신하고 Banking 애플리케이션 서비스로 전달합니다.
     */
    @KafkaListener(topics = ["\${moaje.banking.kafka.topics.asset-transfer-requested}"])
    fun consume(payload: ByteArray) {
        val event = AssetTransferRequestedEvent.parseFrom(payload)
        transferEventService.process(event.toDomainEvent())
    }

    /**
     * protobuf 기반 Kafka 메시지를 Banking 도메인 이벤트 객체로 변환합니다.
     */
    private fun AssetTransferRequestedEvent.toDomainEvent(): TransferRequestedEvent {
        return TransferRequestedEvent(
            eventId = eventId,
            transferId = transferId,
            assetTransactionId = assetTransactionId,
            ci = ci,
            userName = userName,
            phoneNumber = phoneNumber,
            requesterUserId = userId.toString(),
            withdrawalAccountId = withdrawalAccountId,
            depositBankCode = depositBankCode,
            depositAccountNumber = depositAccountNumber,
            amount = Money(BigDecimal.valueOf(amount.amount), amount.currency),
            occurredAt = Instant.parse(occurredAt),
        )
    }
}
