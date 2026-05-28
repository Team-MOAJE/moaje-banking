package com.moaje.banking.domain.common

import java.math.BigDecimal

data class Money(
    val amount: BigDecimal,
    val currency: String = "KRW",
) {
    init {
        require(amount > BigDecimal.ZERO) { "송금 금액은 0보다 커야 합니다." }
        require(currency.isNotBlank()) { "통화 코드는 비어 있을 수 없습니다." }
    }

    companion object {
        fun krw(amount: Long): Money = Money(amount = BigDecimal(amount), currency = "KRW")
    }
}
