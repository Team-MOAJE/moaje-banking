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
)
