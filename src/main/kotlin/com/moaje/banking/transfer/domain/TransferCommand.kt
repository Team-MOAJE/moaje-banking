package com.moaje.banking.transfer.domain

import com.moaje.banking.common.domain.Money
import java.time.Instant

data class TransferCommand(
    val principalId: PrincipalId,
    val idempotencyKey: String,
    val withdrawalAccountId: Long,
    val depositBankCode: String,
    val depositAccountNumber: String,
    val amount: Money,
    val requestedAt: Instant = Instant.now(),
) {
    init {
        require(idempotencyKey.isNotBlank()) { "멱등성 키는 비어 있을 수 없습니다." }
    }
}
