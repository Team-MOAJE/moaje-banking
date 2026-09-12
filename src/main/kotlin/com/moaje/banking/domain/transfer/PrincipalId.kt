package com.moaje.banking.domain.transfer

@JvmInline
value class PrincipalId(val value: String) {
    init {
        require(value.isNotBlank()) { "인증 사용자 식별자는 비어 있을 수 없습니다." }
    }
}
