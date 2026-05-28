package com.moaje.banking.repository.routing

import com.moaje.banking.domain.routing.BankRoutingStatus
import org.springframework.data.jpa.repository.JpaRepository

interface BankRoutingStatusRepository : JpaRepository<BankRoutingStatus, String> {
    fun findByAvailableTrue(): List<BankRoutingStatus>

    fun existsByBankCodeAndAvailableTrue(bankCode: String): Boolean
}
