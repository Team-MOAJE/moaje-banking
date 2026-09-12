package com.moaje.banking.repository.outbox

import com.moaje.banking.domain.outbox.BankingOutboxEvent
import com.moaje.banking.domain.outbox.BankingOutboxStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface BankingOutboxEventRepository : JpaRepository<BankingOutboxEvent, String> {
    @Query(
        """
        select event.eventId
        from BankingOutboxEvent event
        where event.status = com.moaje.banking.domain.outbox.BankingOutboxStatus.PENDING
          and event.nextAttemptAt <= :now
          and (event.lockedUntil is null or event.lockedUntil <= :now)
        order by event.createdAt asc
        """,
    )
    fun findPublishableEventIds(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<String>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update BankingOutboxEvent event
           set event.lockedAt = :now,
               event.lockedUntil = :lockedUntil,
               event.lockOwner = :lockOwner
         where event.eventId = :eventId
           and event.status = :status
           and event.nextAttemptAt <= :now
           and (event.lockedUntil is null or event.lockedUntil <= :now)
        """,
    )
    fun claimPublishable(
        @Param("eventId") eventId: String,
        @Param("now") now: Instant,
        @Param("lockedUntil") lockedUntil: Instant,
        @Param("lockOwner") lockOwner: String,
        @Param("status") status: BankingOutboxStatus = BankingOutboxStatus.PENDING,
    ): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from BankingOutboxEvent event where event.eventId = :eventId")
    fun findByEventIdForUpdate(@Param("eventId") eventId: String): BankingOutboxEvent?

}
