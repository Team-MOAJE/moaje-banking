package com.moaje.banking.domain.reconciliation

import com.moaje.banking.common.id.TsidGeneratedValue
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(
    name = "reconciliation_history",
    indexes = [
        Index(name = "idx_reconciliation_history_target_date", columnList = "target_date"),
        Index(name = "idx_reconciliation_history_status", columnList = "status"),
    ],
)
class ReconciliationHistory(
    @Id
    @TsidGeneratedValue
    @Column(name = "rec_id", nullable = false)
    var recId: Long? = null,

    @Column(name = "target_date", nullable = false)
    var targetDate: LocalDate,

    @Column(name = "unmatched_count", nullable = false)
    var unmatchedCount: Int = 0,

    @Column(name = "resolved_count", nullable = false)
    var resolvedCount: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: ReconciliationStatus = ReconciliationStatus.IN_PROGRESS,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
