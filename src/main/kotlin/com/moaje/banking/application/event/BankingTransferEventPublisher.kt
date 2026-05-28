package com.moaje.banking.application.event

import com.moaje.banking.common.restclient.MockBankingTransferResult
import com.moaje.banking.domain.kftc.KftcApiLogStatus
import com.moaje.events.banking.BankingTransferCompletedEvent
import com.moaje.events.banking.BankingTransferFailedEvent
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
@ConfigurationProperties(prefix = "moaje.banking.kafka.topics")
class BankingKafkaTopicsProperties {
    var accountCreated: String = "account_created_events"
    var assetTransferRequested: String = "moaje.asset.transfer-requested"
    var bankingTransferCompleted: String = "moaje.banking.transfer-completed"
    var bankingTransferFailed: String = "moaje.banking.transfer-failed"
}

@Component
class BankingTransferEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, ByteArray>,
    private val topics: BankingKafkaTopicsProperties,
) {
    /**
     * 외부 금융망 처리 결과에 따라 Asset 서비스가 소비할 성공 또는 실패 이벤트를 Kafka로 발행합니다.
     */
    fun publish(
        bankingResult: MockBankingTransferResult,
        transferId: Long,
        assetTransactionId: Long,
    ) {
        when (bankingResult.status) {
            KftcApiLogStatus.SUCCESS -> publishCompleted(bankingResult, transferId, assetTransactionId)
            KftcApiLogStatus.FAILED,
            KftcApiLogStatus.REVERSED,
            -> publishFailed(bankingResult, transferId, assetTransactionId)
            KftcApiLogStatus.SENT,
            KftcApiLogStatus.TIMEOUT,
            -> Unit
        }
    }

    /**
     * 금융망 이체 성공 결과를 protobuf 이벤트로 만들어 완료 토픽에 전송합니다.
     */
    private fun publishCompleted(
        bankingResult: MockBankingTransferResult,
        transferId: Long,
        assetTransactionId: Long,
    ) {
        val event = BankingTransferCompletedEvent.newBuilder()
            .setEventId(UUID.randomUUID().toString())
            .setTransferId(transferId)
            .setAssetTransactionId(assetTransactionId)
            .setExternalTransactionId(bankingResult.externalTransactionId.orEmpty())
            .setResponseCode(bankingResult.responseCode.orEmpty())
            .setOccurredAt(Instant.now().toString())
            .build()

        kafkaTemplate.send(topics.bankingTransferCompleted, transferId.toString(), event.toByteArray())
    }

    /**
     * 금융망 이체 실패 결과를 protobuf 이벤트로 만들어 실패 토픽에 전송합니다.
     */
    private fun publishFailed(
        bankingResult: MockBankingTransferResult,
        transferId: Long,
        assetTransactionId: Long,
    ) {
        val event = BankingTransferFailedEvent.newBuilder()
            .setEventId(UUID.randomUUID().toString())
            .setTransferId(transferId)
            .setAssetTransactionId(assetTransactionId)
            .setResponseCode(bankingResult.responseCode.orEmpty())
            .setReason(bankingResult.failureReason.orEmpty())
            .setOccurredAt(Instant.now().toString())
            .build()

        kafkaTemplate.send(topics.bankingTransferFailed, transferId.toString(), event.toByteArray())
    }
}
