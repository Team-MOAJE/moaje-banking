package com.moaje.banking.common.restclient

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "moaje.banking.external-api")
data class BankingExternalApiProperties(
    val baseUrl: String = "http://localhost:8081/",
    val hmacSecret: String = "Moaje-banking-secret",
    val connectTimeoutMs: Long = 3_000,
    val readTimeoutMs: Long = 30_000,
    val transferAuthCi: String = "MOAJE_TRANSFER_AUTH",
    val transferAuthUserName: String = "MOAJE_BANKING",
    val transferAuthPhoneNumber: String = "MOAJE_BANKING",
)
