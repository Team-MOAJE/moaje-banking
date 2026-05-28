package com.moaje.banking.domain.reconciliation

import com.moaje.banking.repository.reconciliation.ReconciliationHistoryRepository
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DataJpaTest
class ReconciliationHistoryRepositoryTest(
    @Autowired private val repository: ReconciliationHistoryRepository,
) {
    @Test
    @DisplayName("대사 대상일로 대사 이력을 조회한다")
    fun findByTargetDate() {
        val targetDate = LocalDate.of(2026, 5, 25)
        val saved = repository.save(
            ReconciliationHistory(
                targetDate = targetDate,
                unmatchedCount = 3,
                resolvedCount = 1,
                status = ReconciliationStatus.IN_PROGRESS,
            ),
        )

        val histories = repository.findByTargetDate(targetDate)

        assertNotNull(saved.recId)
        assertEquals(1, histories.size)
        assertEquals(3, histories.first().unmatchedCount)
    }

    @Test
    @DisplayName("특정 일자의 진행 중인 대사 작업 존재 여부를 확인한다")
    fun existsByTargetDateAndStatus() {
        val targetDate = LocalDate.of(2026, 5, 25)
        repository.save(
            ReconciliationHistory(
                targetDate = targetDate,
                status = ReconciliationStatus.IN_PROGRESS,
            ),
        )

        assertTrue(repository.existsByTargetDateAndStatus(targetDate, ReconciliationStatus.IN_PROGRESS))
    }
}
