package com.moaje.banking.repository.transfer

import com.moaje.banking.domain.transfer.BankingTransfer
import com.moaje.banking.domain.transfer.OperationType
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface BankingTransferRepository : JpaRepository<BankingTransfer, Long> {
    fun findByPrincipalIdAndOperationTypeAndIdempotencyKey(
        principalId: String,
        operationType: OperationType,
        idempotencyKey: String,
    ): BankingTransfer?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from BankingTransfer t where t.transferId = :transferId")
    fun findByIdForUpdate(@Param("transferId") transferId: Long): BankingTransfer?

    @Query(
        """
        select t.transferId
        from BankingTransfer t
        where t.reconciliationRetryExhausted = false
          and (t.nextReconciliationAt is null or t.nextReconciliationAt <= :now)
          and (t.reconciliationLockedUntil is null or t.reconciliationLockedUntil <= :now)
          and (
            (t.status = com.moaje.banking.domain.transfer.BankingTransferStatus.UNKNOWN and t.reconciliationRequired = true)
            or (t.status = com.moaje.banking.domain.transfer.BankingTransferStatus.PROCESSING
                and t.processingStartedAt is not null
                and t.processingStartedAt <= :processingStartedBefore)
          )
        order by t.updatedAt asc
        """,
    )
    fun findTransferIdsForReconciliation(
        @Param("now") now: LocalDateTime,
        @Param("processingStartedBefore") processingStartedBefore: LocalDateTime,
        pageable: Pageable,
    ): List<Long>

    /**
     * 여러 Banking 인스턴스가 같은 거래를 동시에 대사하면 Mock 조회와 Outbox 생성이 중복될 수 있다.
     * 그래서 후보 조회 결과를 믿지 않고, DB update 조건으로 lease를 획득한 Worker만 외부 조회를 진행한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update BankingTransfer t
        set t.reconciliationLockedAt = :now,
            t.reconciliationLockedUntil = :lockedUntil,
            t.reconciliationLockOwner = :lockOwner
        where t.transferId = :transferId
          and t.reconciliationRetryExhausted = false
          and (t.nextReconciliationAt is null or t.nextReconciliationAt <= :now)
          and (t.reconciliationLockedUntil is null or t.reconciliationLockedUntil <= :now)
          and (
            (t.status = com.moaje.banking.domain.transfer.BankingTransferStatus.UNKNOWN and t.reconciliationRequired = true)
            or (t.status = com.moaje.banking.domain.transfer.BankingTransferStatus.PROCESSING
                and t.processingStartedAt is not null
                and t.processingStartedAt <= :processingStartedBefore)
          )
        """,
    )
    fun claimTransferForReconciliation(
        @Param("transferId") transferId: Long,
        @Param("now") now: LocalDateTime,
        @Param("lockedUntil") lockedUntil: LocalDateTime,
        @Param("lockOwner") lockOwner: String,
        @Param("processingStartedBefore") processingStartedBefore: LocalDateTime,
    ): Int

    @Query(
        """
        select count(t)
        from BankingTransfer t
        where t.reconciliationRetryExhausted = false
          and (t.nextReconciliationAt is null or t.nextReconciliationAt <= :now)
          and (t.reconciliationLockedUntil is null or t.reconciliationLockedUntil <= :now)
          and (
            (t.status = com.moaje.banking.domain.transfer.BankingTransferStatus.UNKNOWN and t.reconciliationRequired = true)
            or (t.status = com.moaje.banking.domain.transfer.BankingTransferStatus.PROCESSING
                and t.processingStartedAt is not null
                and t.processingStartedAt <= :processingStartedBefore)
          )
        """,
    )
    fun countTransferReconciliationPending(
        @Param("now") now: LocalDateTime,
        @Param("processingStartedBefore") processingStartedBefore: LocalDateTime,
    ): Long

    fun countByReconciliationRetryExhaustedTrue(): Long

    @Query(
        """
        select count(t)
        from BankingTransfer t
        where t.reconciliationLockedUntil is not null
          and t.reconciliationLockedUntil > :now
        """,
    )
    fun countTransferReconciliationLocked(@Param("now") now: LocalDateTime): Long
}
