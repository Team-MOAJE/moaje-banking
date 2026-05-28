package com.moaje.banking.domain.routing

import com.moaje.banking.repository.routing.BankRoutingStatusRepository
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DataJpaTest
class BankRoutingStatusRepositoryTest(
    @Autowired private val repository: BankRoutingStatusRepository,
) {
    @Test
    @DisplayName("통신 가능한 은행 목록을 조회한다")
    fun findByAvailableTrue() {
        repository.save(BankRoutingStatus(bankCode = "088", bankName = "신한은행", available = true))
        repository.save(BankRoutingStatus(bankCode = "004", bankName = "국민은행", available = false))

        val availableBanks = repository.findByAvailableTrue()

        assertEquals(1, availableBanks.size)
        assertEquals("088", availableBanks.first().bankCode)
    }

    @Test
    @DisplayName("특정 은행이 통신 가능한 상태인지 확인한다")
    fun existsByBankCodeAndAvailableTrue() {
        repository.save(BankRoutingStatus(bankCode = "020", bankName = "우리은행", available = true))

        assertTrue(repository.existsByBankCodeAndAvailableTrue("020"))
    }
}
