package com.moaje.banking.routing.persistence

import com.moaje.banking.routing.domain.BankRoutingStatus
import org.springframework.data.jpa.repository.JpaRepository

interface BankRoutingStatusRepository : JpaRepository<BankRoutingStatus, String> {
    fun findByAvailableTrue(): List<BankRoutingStatus>

    fun existsByBankCodeAndAvailableTrue(bankCode: String): Boolean
}
