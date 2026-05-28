package com.moaje.banking.application.event

import com.moaje.banking.domain.account.OpenAccountResult
import com.moaje.events.banking.AccountCreatedEvent
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class BankingAccountEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, ByteArray>,
    private val topics: BankingKafkaTopicsProperties,
) {
    /**
     * 목업 뱅킹 계좌개설 완료 사실을 Kafka 이벤트로 발행합니다.
     */
    fun publishAccountCreated(result: OpenAccountResult) {
        val event = AccountCreatedEvent.newBuilder()
            .setEventId(UUID.randomUUID().toString())
            .setUserId(result.userId)
            .setAccountNumber(result.accountNumber)
            .setBankCode(result.bankCode)
            .setProductName(result.productName)
            .setInitialBalance(result.balance)
            .setCreatedAt(result.createdAt.toString())
            .setCanceledAt(result.canceledAt?.toString().orEmpty())
            .setOccurredAt(Instant.now().toString())
            .build()

        kafkaTemplate.send(topics.accountCreated, result.accountNumber, event.toByteArray())
    }
}
