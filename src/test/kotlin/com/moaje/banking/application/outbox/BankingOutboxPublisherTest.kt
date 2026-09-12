package com.moaje.banking.application.outbox

import com.moaje.banking.domain.outbox.BankingOutboxEvent
import com.moaje.banking.domain.outbox.BankingOutboxStatus
import com.moaje.banking.repository.outbox.BankingOutboxEventRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.CompletableFuture

@DataJpaTest
@Suppress("UNCHECKED_CAST")
class BankingOutboxPublisherTest(
    @Autowired private val outboxEventRepository: BankingOutboxEventRepository,
    @Autowired private val transactionManager: PlatformTransactionManager,
) {
    private lateinit var kafkaTemplate: KafkaTemplate<String, ByteArray>
    private lateinit var properties: BankingOutboxPublisherProperties
    private lateinit var publisher: BankingOutboxPublisher
    private lateinit var maintenanceService: BankingOutboxMaintenanceService

    @BeforeEach
    fun setUp() {
        outboxEventRepository.deleteAll()
        outboxEventRepository.flush()
        kafkaTemplate = Mockito.mock(KafkaTemplate::class.java) as KafkaTemplate<String, ByteArray>
        properties = BankingOutboxPublisherProperties().apply {
            enabled = true
            batchSize = 10
            leaseDurationMs = 30_000
            maxAttempts = 3
            baseBackoffMs = 1_000
            maxBackoffMs = 10_000
            kafkaSendTimeoutMs = 1_000
        }
        publisher = BankingOutboxPublisher(
            outboxEventRepository = outboxEventRepository,
            kafkaTemplate = kafkaTemplate,
            properties = properties,
            transactionTemplate = TransactionTemplate(transactionManager),
        )
        maintenanceService = BankingOutboxMaintenanceService(
            outboxEventRepository = outboxEventRepository,
            transactionTemplate = TransactionTemplate(transactionManager),
        )
    }

    @AfterEach
    fun tearDown() {
        outboxEventRepository.deleteAll()
        outboxEventRepository.flush()
    }

    @Test
    @DisplayName("Kafka broker ack를 확인한 경우에만 Outbox를 PUBLISHED로 변경한다")
    fun marksPublishedOnlyAfterKafkaAck() {
        // given: 아직 발행되지 않은 Outbox 이벤트가 있다.
        val event = saveOutbox(eventId = "event-1")
        Mockito.`when`(
            kafkaTemplate.send(anyProducerRecord()),
        )
            .thenReturn(CompletableFuture.completedFuture(Mockito.mock(SendResult::class.java) as SendResult<String, ByteArray>))

        // when
        val publishedCount = publisher.publishBatch()

        // then: Kafka send future가 성공한 뒤에만 PUBLISHED와 publishedAt이 기록된다.
        val published = outboxEventRepository.findById("event-1").orElseThrow()
        assertThat(publishedCount).isEqualTo(1)
        assertThat(published.status).isEqualTo(BankingOutboxStatus.PUBLISHED)
        assertThat(published.publishedAt).isNotNull()
        assertThat(published.attemptCount).isEqualTo(1)
        assertThat(published.lockOwner).isNull()
    }

    @Test
    @DisplayName("Kafka 전송 실패는 DLT가 아니라 PENDING 유지와 다음 재시도 시각으로 기록한다")
    fun keepsPendingAndSchedulesNextAttemptWhenKafkaSendFails() {
        // given
        val beforePublish = Instant.now()
        val event = saveOutbox(eventId = "event-2")
        val failedFuture = CompletableFuture<SendResult<String, ByteArray>>()
        failedFuture.completeExceptionally(IllegalStateException("broker down"))
        Mockito.`when`(
            kafkaTemplate.send(anyProducerRecord()),
        ).thenReturn(failedFuture)

        // when
        val publishedCount = publisher.publishBatch()

        // then: Producer 측 실패는 Kafka DLT가 아니라 Banking DB Outbox에 남는다.
        val failed = outboxEventRepository.findById("event-2").orElseThrow()
        assertThat(publishedCount).isZero()
        assertThat(failed.status).isEqualTo(BankingOutboxStatus.PENDING)
        assertThat(failed.attemptCount).isEqualTo(1)
        assertThat(failed.nextAttemptAt).isAfter(beforePublish)
        assertThat(failed.lastFailureReason).contains("broker down")
        assertThat(failed.eventId).isEqualTo("event-2")
    }

    @Test
    @DisplayName("최대 자동 재시도 횟수를 소진하면 RETRY_EXHAUSTED로 보존한다")
    fun marksRetryExhaustedWhenMaxAttemptsExceeded() {
        // given
        properties.maxAttempts = 1
        val event = saveOutbox(eventId = "event-3")
        val failedFuture = CompletableFuture<SendResult<String, ByteArray>>()
        failedFuture.completeExceptionally(IllegalStateException("broker down"))
        Mockito.`when`(
            kafkaTemplate.send(anyProducerRecord()),
        ).thenReturn(failedFuture)

        // when
        publisher.publishBatch()

        // then
        val exhausted = outboxEventRepository.findById("event-3").orElseThrow()
        assertThat(exhausted.status).isEqualTo(BankingOutboxStatus.RETRY_EXHAUSTED)
        assertThat(exhausted.attemptCount).isEqualTo(1)
        assertThat(exhausted.lastFailureReason).contains("broker down")
    }

    @Test
    @DisplayName("다른 Publisher가 Lease를 보유 중이면 같은 이벤트를 동시에 발행하지 않는다")
    fun doesNotPublishEventLockedByAnotherPublisher() {
        // given: 다른 인스턴스가 아직 만료되지 않은 Lease를 잡고 있다.
        val event = saveOutbox(eventId = "event-4")
        outboxEventRepository.claimPublishable(
            eventId = event.eventId,
            now = Instant.now(),
            lockedUntil = Instant.now().plusSeconds(60),
            lockOwner = "other-publisher",
        )

        // when
        val publishedCount = publisher.publishBatch()

        // then
        assertThat(publishedCount).isZero()
        Mockito.verifyNoInteractions(kafkaTemplate)
        val locked = outboxEventRepository.findById("event-4").orElseThrow()
        assertThat(locked.status).isEqualTo(BankingOutboxStatus.PENDING)
        assertThat(locked.lockOwner).isEqualTo("other-publisher")
    }

    @Test
    @DisplayName("운영자 수동 재발행 대상은 같은 eventId를 유지한 채 PENDING으로 되돌릴 수 있다")
    fun manuallyResetsRetryExhaustedEventToPendingWithSameEventId() {
        // given
        val event = saveOutbox(eventId = "event-5")
        val failedFuture = CompletableFuture<SendResult<String, ByteArray>>()
        failedFuture.completeExceptionally(IllegalStateException("broker down"))
        properties.maxAttempts = 1
        Mockito.`when`(
            kafkaTemplate.send(anyProducerRecord()),
        ).thenReturn(failedFuture)
        publisher.publishBatch()

        // when
        val reset = maintenanceService.resetRetryExhausted("event-5")

        // then: 재발행은 새 eventId를 만들지 않고 기존 Outbox 레코드를 다시 발행 대상으로 만든다.
        val pending = outboxEventRepository.findById("event-5").orElseThrow()
        assertThat(reset).isTrue()
        assertThat(pending.eventId).isEqualTo("event-5")
        assertThat(pending.status).isEqualTo(BankingOutboxStatus.PENDING)
        assertThat(pending.lockOwner).isNull()
    }

    @Test
    @DisplayName("Kafka 본문을 해석할 수 없어도 DLT 복구에 필요한 식별자를 Header로 전달한다")
    fun publishesRecoveryIdentifiersAsHeaders() {
        // given: Consumer가 역직렬화하지 못할 수도 있는 Outbox 이벤트를 준비한다.
        saveOutbox(eventId = "event-header")
        Mockito.`when`(
            kafkaTemplate.send(anyProducerRecord()),
        ).thenReturn(CompletableFuture.completedFuture(Mockito.mock(SendResult::class.java) as SendResult<String, ByteArray>))

        // when
        publisher.publishBatch()

        // then: 개인정보 없이 event, aggregate, account 식별 단서를 별도 Header로 보존한다.
        val captor = ArgumentCaptor.forClass(ProducerRecord::class.java) as ArgumentCaptor<ProducerRecord<String, ByteArray>>
        Mockito.verify(kafkaTemplate).send(captor.capture())
        val record = captor.value
        assertThat(record.headers().lastHeader(BankingOutboxPublisher.EVENT_ID_HEADER).value().decodeToString())
            .isEqualTo("event-header")
        assertThat(record.headers().lastHeader(BankingOutboxPublisher.AGGREGATE_ID_HEADER).value().decodeToString())
            .isEqualTo("100")
        assertThat(record.headers().lastHeader(BankingOutboxPublisher.ACCOUNT_ID_HEADER).value().decodeToString())
            .isEqualTo("100")
    }

    private fun saveOutbox(eventId: String): BankingOutboxEvent {
        return outboxEventRepository.saveAndFlush(
            BankingOutboxEvent(
                eventId = eventId,
                aggregateType = BankingOutboxEvent.AGGREGATE_TYPE_BANKING_TRANSFER,
                aggregateId = "100",
                eventType = "BankingTransferCompletedEvent",
                topic = "moaje.banking.transfer-completed",
                messageKey = "100",
                payload = "payload-$eventId".toByteArray(),
                nextAttemptAt = Instant.now().minusSeconds(1),
            ),
        )
    }

    private fun anyProducerRecord(): ProducerRecord<String, ByteArray> {
        ArgumentMatchers.any<ProducerRecord<String, ByteArray>>()
        return ProducerRecord("matcher-placeholder", "matcher-placeholder", byteArrayOf())
    }
}
