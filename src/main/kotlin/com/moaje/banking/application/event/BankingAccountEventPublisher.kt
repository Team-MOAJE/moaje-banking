package com.moaje.banking.application.event

import com.moaje.banking.domain.account.OpenAccountResult
import com.moaje.banking.domain.outbox.BankingOutboxEvent
import com.moaje.banking.repository.outbox.BankingOutboxEventRepository
import com.moaje.events.banking.AccountCreatedEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class BankingAccountEventPublisher(
    private val outboxRepository: BankingOutboxEventRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val topics: BankingKafkaTopicsProperties,
) {
    /**
     * 계좌 연결 저장과 같은 DB 트랜잭션에 AccountCreated Outbox를 기록한다.
     * Kafka 직접 발행을 피해야 DB commit 뒤 프로세스가 종료돼도 Scheduler가 이벤트를 다시 발행할 수 있다.
     */
    fun publishAccountCreated(result: OpenAccountResult) {
        val event = AccountCreatedEvent.newBuilder()
            .setEventId(UUID.randomUUID().toString())
            .setUserId(result.userId)
            .setAccountId(result.accountId)
            .setBankCode(result.bankCode)
            .setProductName(result.productName)
            .setInitialBalance(result.balance)
            .setCreatedAt(result.createdAt.toString())
            .setCanceledAt(result.canceledAt?.toString().orEmpty())
            .setOccurredAt(Instant.now().toString())
            .build()
        outboxRepository.save(
            BankingOutboxEvent(
                eventId = event.eventId,
                aggregateType = BankingOutboxEvent.AGGREGATE_TYPE_BANKING_ACCOUNT,
                aggregateId = result.accountId.toString(),
                eventType = "AccountCreatedEvent",
                topic = topics.accountCreated,
                messageKey = result.accountId.toString(),
                payload = event.toByteArray(),
            ),
        )
        applicationEventPublisher.publishEvent(
            BankingTransferEventPublisher.TransferOutboxEvent(event.eventId),
        )
    }
}
