package com.moaje.banking.reconciliation.persistence

import com.moaje.banking.reconciliation.domain.ReconciliationHistory
import com.moaje.banking.reconciliation.domain.ReconciliationStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface ReconciliationHistoryRepository : JpaRepository<ReconciliationHistory, Long> {
    fun findByTargetDate(targetDate: LocalDate): List<ReconciliationHistory>

    fun findByStatus(status: ReconciliationStatus): List<ReconciliationHistory>

    fun existsByTargetDateAndStatus(
        targetDate: LocalDate,
        status: ReconciliationStatus,
    ): Boolean
}
