package com.moaje.banking.account.persistence

import com.moaje.banking.account.domain.BankingAccount
import org.springframework.data.jpa.repository.JpaRepository

interface BankingAccountRepository : JpaRepository<BankingAccount, Long> {
    fun findByProviderAccountId(providerAccountId: String): BankingAccount?
}
