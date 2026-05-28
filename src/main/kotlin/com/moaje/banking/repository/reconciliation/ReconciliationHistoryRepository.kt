package com.moaje.banking.repository.reconciliation

import com.moaje.banking.domain.reconciliation.ReconciliationHistory
import com.moaje.banking.domain.reconciliation.ReconciliationStatus
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
