package com.moaje.banking.transfer.domain

// MVC 리졸버가 반환한 객체를 그대로 받을 수 있도록 일반 값 객체로 사용한다.
// 현재 MVC 호출 경로에서는 value class를 받으면 argument type mismatch가 발생한다.
data class PrincipalId(val value: String) {
    init {
        require(value.isNotBlank()) { "인증 사용자 식별자는 비어 있을 수 없습니다." }
    }
}
