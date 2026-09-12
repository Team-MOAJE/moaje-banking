package com.moaje.banking.domain.outbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Lob
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

@Entity
@Table(
    name = "banking_outbox",
    indexes = [
        Index(name = "idx_banking_outbox_publishable", columnList = "status,next_attempt_at,locked_until"),
        Index(name = "idx_banking_outbox_aggregate", columnList = "aggregate_type,aggregate_id"),
    ],
)
class BankingOutboxEvent(
    @Id
    @Column(name = "event_id", nullable = false, length = 64)
    var eventId: String,

    @Column(name = "aggregate_type", nullable = false, length = 80)
    var aggregateType: String,

    @Column(name = "aggregate_id", nullable = false, length = 80)
    var aggregateId: String,

    @Column(name = "event_type", nullable = false, length = 120)
    var eventType: String,

    @Column(name = "topic", nullable = false, length = 200)
    var topic: String,

    @Column(name = "message_key", nullable = false, length = 120)
    var messageKey: String,

    @Lob
    // V1 migration의 BLOB과 매핑을 일치시킨다. 기본 길이로 두면 MySQL에서 TINYBLOB을 기대해 validate가 실패한다.
    // 기존 DB 컬럼을 축소하거나 검증을 끄지 않고, 이미 정한 이벤트 본문 저장 형식을 코드에도 명시한다.
    @Column(name = "payload", nullable = false, columnDefinition = "BLOB")
    var payload: ByteArray,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: BankingOutboxStatus = BankingOutboxStatus.PENDING,

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,

    @Column(name = "last_failure_reason", length = 500)
    var lastFailureReason: String? = null,

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant = Instant.now(),

    @Column(name = "published_at")
    var publishedAt: Instant? = null,

    @Column(name = "locked_at")
    var lockedAt: Instant? = null,

    @Column(name = "locked_until")
    var lockedUntil: Instant? = null,

    @Column(name = "lock_owner", length = 120)
    var lockOwner: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun isLockedBy(owner: String): Boolean = lockOwner == owner

    fun markPublished(now: Instant) {
        status = BankingOutboxStatus.PUBLISHED
        attemptCount += 1
        publishedAt = now
        lastFailureReason = null
        clearLease()
    }

    fun recordPublishFailure(
        reason: String,
        now: Instant,
        maxAttempts: Int,
        baseBackoff: Duration,
        maxBackoff: Duration,
    ) {
        attemptCount += 1
        lastFailureReason = reason.take(500)
        publishedAt = null
        clearLease()

        if (attemptCount >= maxAttempts) {
            status = BankingOutboxStatus.RETRY_EXHAUSTED
            nextAttemptAt = now
            return
        }

        status = BankingOutboxStatus.PENDING
        nextAttemptAt = now.plus(calculateBackoff(baseBackoff, maxBackoff))
    }

    fun resetForManualRetry(now: Instant) {
        status = BankingOutboxStatus.PENDING
        lastFailureReason = null
        nextAttemptAt = now
        publishedAt = null
        clearLease()
    }

    private fun clearLease() {
        lockedAt = null
        lockedUntil = null
        lockOwner = null
    }

    // 네트워크 CS지식 응용 - 지수백오프 방식으로 Kafka publish 재시도
    private fun calculateBackoff(baseBackoff: Duration, maxBackoff: Duration): Duration {
        var delayMillis = baseBackoff.toMillis().coerceAtLeast(1)
        repeat((attemptCount - 1).coerceAtLeast(0)) {
            delayMillis = (delayMillis * 2).coerceAtMost(maxBackoff.toMillis().coerceAtLeast(1))
        }
        return Duration.ofMillis(delayMillis.coerceAtMost(maxBackoff.toMillis().coerceAtLeast(1)))
    }

    companion object {
        const val AGGREGATE_TYPE_BANKING_TRANSFER = "BANKING_TRANSFER"
        const val AGGREGATE_TYPE_BANKING_ACCOUNT = "BANKING_ACCOUNT"
    }
}
