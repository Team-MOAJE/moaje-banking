package com.moaje.banking.account.application.model

import java.time.Instant
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import io.swagger.v3.oas.annotations.media.Schema

data class OpenAccountResult(
    // 앱의 JavaScript Number가 큰 정수 ID를 반올림하지 않도록 응답에서만 문자열로 보낸다.
    @get:JsonSerialize(using = ToStringSerializer::class)
    @get:Schema(type = "string", example = "123456789012345678")
    val accountId: Long,
    val userId: String,
    val bankCode: String,
    val productName: String,
    val balance: Long,
    val status: String,
    val createdAt: Instant,
    val canceledAt: Instant?,
    val bankName: String? = null,
    @get:Schema(description = "개설된 실제 계좌번호. 표시·수취용이며 본인 계좌 조회·출금에는 accountId를 사용한다.")
    val accountNumber: String,
) {
    // Kafka에는 원번호 대신 끝 네 자리만 남긴 표시값을 보낸다.
    // 짧은 값도 전체가 노출되지 않도록 네 자리 이하는 전부 가린다.
    val maskedAccountNumber: String
        get() = if (accountNumber.length <= 4) "*".repeat(accountNumber.length)
        else "*".repeat(accountNumber.length - 4) + accountNumber.takeLast(4)
}
