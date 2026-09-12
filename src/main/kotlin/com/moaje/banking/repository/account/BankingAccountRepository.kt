package com.moaje.banking.repository.account

import com.moaje.banking.domain.account.BankingAccount
import org.springframework.data.jpa.repository.JpaRepository

interface BankingAccountRepository : JpaRepository<BankingAccount, Long> {
    fun findByProviderAccountId(providerAccountId: String): BankingAccount?
}
