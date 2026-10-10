package com.moaje.banking.transfer.domain

// 사용자 ID를 다른 문자열과 구분하는 업무용 타입이다.
// MVC에서는 String으로 받고 Controller에서 이 타입으로 변환한다.
@JvmInline
value class PrincipalId(val value: String) {
    init {
        require(value.isNotBlank()) { "인증 사용자 식별자는 비어 있을 수 없습니다." }
    }
}
