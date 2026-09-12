package com.moaje.banking.application.event

import com.moaje.banking.common.restclient.MockBankingTransferResult
import com.moaje.banking.domain.kftc.KftcApiLogStatus
import com.moaje.banking.domain.outbox.BankingOutboxEvent
import com.moaje.banking.domain.transfer.TransferCommand
import com.moaje.banking.repository.outbox.BankingOutboxEventRepository
import com.moaje.common.Money as ProtoMoney
import com.moaje.events.banking.BankingTransferCompletedEvent
import com.moaje.events.banking.BankingTransferFailedEvent
import com.moaje.events.banking.BankingTransferReversedEvent
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Kafka Topic (일반상태)
 * 계좌생성 Event
 * 송금완료 Event
 * 송금실패 Event
 */

@Component
@ConfigurationProperties(prefix = "moaje.banking.kafka.topics")
class BankingKafkaTopicsProperties {
    var accountCreated: String = "account_created_events"
    var bankingTransferCompleted: String = "moaje.banking.transfer-completed"
    var bankingTransferFailed: String = "moaje.banking.transfer-failed"
    var bankingTransferReversed: String = "moaje.banking.transfer-reversed"
}

@Component
class BankingTransferEventPublisher(
    private val topics: BankingKafkaTopicsProperties,
    private val outboxEventRepository: BankingOutboxEventRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {
    /**
     * 외부 금융망 처리 결과에 따라 Asset 서비스가 소비할 성공 또는 실패 이벤트를 Outbox에 기록합니다.
     * 실제 Kafka 발행은 BankingOutboxPublisher가 DB 트랜잭션 밖에서 수행합니다.
     */
    fun publish(
        bankingResult: MockBankingTransferResult,
        transferId: Long,
        assetTransactionId: Long,
        command: TransferCommand,
    ) {
        when (bankingResult.status) {
            KftcApiLogStatus.SUCCESS -> publishCompleted(bankingResult, transferId, assetTransactionId, command)
            KftcApiLogStatus.FAILED -> publishFailed(bankingResult, transferId, assetTransactionId, command)
            KftcApiLogStatus.REVERSED -> publishReversed(bankingResult, transferId, command)
            KftcApiLogStatus.TIMEOUT,
            KftcApiLogStatus.SENT,
            -> Unit
        }
    }

    /**
     * UNKNOWN 또는 오래된 PROCESSING을 조회했는데 이미 REVERSED라면 Banking은 원 성공 이벤트도 만들지 못했을 수 있다.
     * Asset이 출금 없이 보상 입금만 반영하지 않도록 원거래 Completed와 Reversed Outbox를 같은 DB 트랜잭션에 함께 저장한다.
     * 두 Topic 사이의 도착 순서는 보장되지 않으므로 실제 순서 역전은 Asset의 pending 보상 모델이 흡수한다.
     */
    fun publishRecoveredReversed(
        bankingResult: MockBankingTransferResult,
        transferId: Long,
        assetTransactionId: Long,
        command: TransferCommand,
    ) {
        publishCompleted(
            bankingResult = bankingResult.copy(status = KftcApiLogStatus.SUCCESS),
            transferId = transferId,
            assetTransactionId = assetTransactionId,
            command = command,
        )
        publishReversed(bankingResult, transferId, command)
    }

    /**
     * 금융망 이체 성공 결과를 protobuf 이벤트로 만들어 완료 토픽용 Outbox에 저장합니다.
     */
    private fun publishCompleted(
        bankingResult: MockBankingTransferResult,
        transferId: Long,
        assetTransactionId: Long,
        command: TransferCommand,
    ) {
        val eventId = UUID.randomUUID().toString()
        val event = BankingTransferCompletedEvent.newBuilder()
            .setEventId(eventId)
            .setTransferId(transferId)
            .setAssetTransactionId(assetTransactionId)
            .setExternalTransactionId(bankingResult.externalTransactionId.orEmpty())
            .setResponseCode(bankingResult.responseCode.orEmpty())
            .setOccurredAt(Instant.now().toString())
            .setUserId(command.principalId.value)
            .setAccountId(command.withdrawalAccountId)
            .setAmount(command.amount.toProtoMoney())
            .build()

        saveOutbox(
            eventId = eventId,
            transferId = transferId,
            messageKey = command.withdrawalAccountId.toString(),
            eventType = "BankingTransferCompletedEvent",
            topic = topics.bankingTransferCompleted,
            payload = event.toByteArray(),
        )

        // 2026.08.25 >> Kafka 즉시반영을 위해 추가
        publishTransferEventToKafka(eventId = eventId)
    }

    /**
     * 금융망 이체 실패 결과를 protobuf 이벤트로 만들어 실패 토픽용 Outbox에 저장합니다.
     */
    private fun publishFailed(
        bankingResult: MockBankingTransferResult,
        transferId: Long,
        assetTransactionId: Long,
        command: TransferCommand,
    ) {
        val eventId = UUID.randomUUID().toString()
        val event = BankingTransferFailedEvent.newBuilder()
            .setEventId(eventId)
            .setTransferId(transferId)
            .setAssetTransactionId(assetTransactionId)
            .setResponseCode(bankingResult.responseCode.orEmpty())
            .setReason(bankingResult.failureReason.orEmpty())
            .setFailureStatus(bankingResult.status.name)
            .setOccurredAt(Instant.now().toString())
            .setUserId(command.principalId.value)
            .setAccountId(command.withdrawalAccountId)
            .setAmount(command.amount.toProtoMoney())
            .build()

        saveOutbox(
            eventId = eventId,
            transferId = transferId,
            messageKey = command.withdrawalAccountId.toString(),
            eventType = "BankingTransferFailedEvent",
            topic = topics.bankingTransferFailed,
            payload = event.toByteArray(),
        )

        // 2026.08.25 >> Kafka 즉시반영을 위해 추가
        publishTransferEventToKafka(eventId = eventId)
    }

    /**
     * 보상은 원거래 실패가 아니라 이미 성공한 금융 효과를 반대로 되돌린 별도 금융 효과다.
     * 따라서 실패 이벤트로 축약하지 않고 원거래 ID와 외부 보상 거래 ID를 함께 가진 독립 이벤트로 기록한다.
     */
    private fun publishReversed(
        bankingResult: MockBankingTransferResult,
        transferId: Long,
        command: TransferCommand,
    ) {
        val eventId = UUID.randomUUID().toString()
        val event = BankingTransferReversedEvent.newBuilder()
            .setEventId(eventId)
            .setTransferId(transferId)
            .setOriginalExternalTransactionId(bankingResult.externalTransactionId.orEmpty())
            .setExternalReversalTransactionId(bankingResult.externalReversalTransactionId.orEmpty())
            .setReason(bankingResult.reversalReason.orEmpty())
            .setOccurredAt((bankingResult.reversedAt ?: Instant.now()).toString())
            .setUserId(command.principalId.value)
            .setAccountId(command.withdrawalAccountId)
            .setAmount(command.amount.toProtoMoney())
            .build()

        saveOutbox(
            eventId = eventId,
            transferId = transferId,
            messageKey = command.withdrawalAccountId.toString(),
            eventType = "BankingTransferReversedEvent",
            topic = topics.bankingTransferReversed,
            payload = event.toByteArray(),
        )
        publishTransferEventToKafka(eventId = eventId)
    }

    private fun saveOutbox(
        eventId: String,
        transferId: Long,
        messageKey: String,
        eventType: String,
        topic: String,
        payload: ByteArray,
    ) {
        outboxEventRepository.save(
            BankingOutboxEvent(
                eventId = eventId,
                aggregateType = BankingOutboxEvent.AGGREGATE_TYPE_BANKING_TRANSFER,
                aggregateId = transferId.toString(),
                eventType = eventType,
                topic = topic,
                messageKey = messageKey,
                payload = payload,
            ),
        )
    }

    private fun com.moaje.banking.domain.common.Money.toProtoMoney(): ProtoMoney {
        return ProtoMoney.newBuilder()
            .setAmount(amount.toLong())
            .setCurrency(currency)
            .build()
    }

    /**
     * Kafka Topic 으로 즉시 publish 하기 위해 만듦
     */
    private fun publishTransferEventToKafka(eventId: String) {
        // eventListener 에게 이벤트를 던짊
        eventPublisher.publishEvent(TransferOutboxEvent(eventId = eventId))
    }

    data class TransferOutboxEvent(val eventId: String)


}
