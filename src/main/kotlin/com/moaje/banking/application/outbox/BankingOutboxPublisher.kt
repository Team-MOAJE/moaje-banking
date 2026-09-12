package com.moaje.banking.application.outbox

import com.moaje.banking.domain.outbox.BankingOutboxEvent
import com.moaje.banking.repository.outbox.BankingOutboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.kafka.core.KafkaTemplate
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

@Component
class BankingOutboxPublisher(
    private val outboxEventRepository: BankingOutboxEventRepository,
    private val kafkaTemplate: KafkaTemplate<String, ByteArray>,
    private val properties: BankingOutboxPublisherProperties,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lockOwner = "${resolveHostName()}-${UUID.randomUUID()}"

    @Scheduled(
        fixedDelayString = "\${moaje.banking.outbox.publisher.fixed-delay-ms:5000}",
        initialDelayString = "\${moaje.banking.outbox.publisher.initial-delay-ms:5000}",
    )
    fun publishPendingEvents() {
        if (!properties.enabled) {
            return
        }
        // 5초단위로 5번의 배치를 실행 (20 * 5)
        repeat(properties.batchRepeats.coerceAtLeast(1)) {
            publishBatch()
        }
    }

    fun publishBatch(): Int {
        val now = Instant.now()
        val eventIds = outboxEventRepository.findPublishableEventIds(
            now = now,
            pageable = PageRequest.of(0, properties.batchSize.coerceAtLeast(1)),
        )

        return eventIds.count { eventId ->
            claim(eventId, now) && publishClaimed(eventId)
        }
    }

    // 단건 message를 빠르게 publish 한다.
    fun fastPublishBySingle(eventId: String): Boolean {
        return claim(eventId, Instant.now()) && publishClaimed(eventId)
    }


    // outbox Table 에서 kafka message를 publisher 가 선점한다.
    private fun claim(eventId: String, now: Instant): Boolean {
        val lockedUntil = now.plusMillis(properties.leaseDurationMs.coerceAtLeast(1)) // publisher 가 선점가능한 시간
        return transactionTemplate.execute {
            outboxEventRepository.claimPublishable(
                eventId = eventId,
                now = now,
                lockedUntil = lockedUntil,
                lockOwner = lockOwner,
            ) == 1 // 현재 publisher 명의로 선점시도(UPDATE) 시 영향받은 row의 갯수가 1 -> 정상적으로 kafka message 를 claim
        } ?: false
    }

    // 선점한 kafka message를 kafka로 발행한다.
    private fun publishClaimed(eventId: String): Boolean {
        val event = outboxEventRepository.findById(eventId).orElse(null) ?: return false
        return try {
            kafkaTemplate
                .send(event.toProducerRecord())
                .get(properties.kafkaSendTimeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            markPublished(event.eventId)
            true
        } catch (exception: Exception) {
            recordFailure(event.eventId, exception)
            false
        }
    }

    // kafka 로 메세지 발행 이후 published 로 상태를 남긴다.
    private fun markPublished(eventId: String) {
        transactionTemplate.executeWithoutResult {
            val event = outboxEventRepository.findByEventIdForUpdate(eventId)
                ?: return@executeWithoutResult
            if (!event.isLockedBy(lockOwner)) {
                log.info("Skip published mark because outbox lease owner changed. eventId={}", eventId)
                return@executeWithoutResult
            }
            event.markPublished(Instant.now())
        }
    }

    // kafka 로 메세지 발행 실패 시 실패 기록을 남긴다.
    private fun recordFailure(eventId: String, exception: Exception) {
        transactionTemplate.executeWithoutResult {
            val event = outboxEventRepository.findByEventIdForUpdate(eventId)
                ?: return@executeWithoutResult
            if (!event.isLockedBy(lockOwner)) {
                log.info("Skip failure mark because outbox lease owner changed. eventId={}", eventId)
                return@executeWithoutResult
            }
            event.recordPublishFailure(
                reason = exception.rootMessage(),
                now = Instant.now(),
                maxAttempts = properties.maxAttempts.coerceAtLeast(1),
                baseBackoff = Duration.ofMillis(properties.baseBackoffMs.coerceAtLeast(1)),
                maxBackoff = Duration.ofMillis(properties.maxBackoffMs.coerceAtLeast(properties.baseBackoffMs.coerceAtLeast(1))),
            )
        }
    }

    private fun Exception.rootMessage(): String {
        val root = generateSequence(this as Throwable) { it.cause }.last()
        return root.message ?: root::class.java.simpleName
    }

    private fun resolveHostName(): String {
        return runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown-host")
    }

    /**
     * Protobuf 본문이 손상되면 Consumer는 transferId조차 꺼낼 수 있으므로 복구용 식별자를 Kafka Header에도 둔다.
     * Header에는 개인정보를 넣지 않고 eventId와 aggregateId, accountId 역할의 messageKey만 넣어 DLT 추적과 계좌 대사를 돕는다.
     */
    private fun BankingOutboxEvent.toProducerRecord(): ProducerRecord<String, ByteArray> =
        ProducerRecord(
            topic,
            null,
            null,
            messageKey,
            payload,
            listOf(
                RecordHeader(EVENT_ID_HEADER, eventId.toByteArray()),
                RecordHeader(AGGREGATE_ID_HEADER, aggregateId.toByteArray()),
                RecordHeader(EVENT_TYPE_HEADER, eventType.toByteArray()),
                RecordHeader(ACCOUNT_ID_HEADER, messageKey.toByteArray()),
            ),
        )

    companion object {
        const val EVENT_ID_HEADER = "moaje-event-id"
        const val AGGREGATE_ID_HEADER = "moaje-aggregate-id"
        const val EVENT_TYPE_HEADER = "moaje-event-type"
        const val ACCOUNT_ID_HEADER = "moaje-account-id"
    }
}
