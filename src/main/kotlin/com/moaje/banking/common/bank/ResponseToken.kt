package com.moaje.banking.common.bank

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * 금융망 Auth 최초 인증시 받아오는 Token
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ResponseToken(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Long,

    // Redis 저장을 위해 직접 계산해서 넣을 절대 만료시간
    val absoluteExpiration: Long = 0L
)