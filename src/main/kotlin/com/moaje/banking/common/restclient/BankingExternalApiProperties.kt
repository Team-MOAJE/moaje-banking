package com.moaje.banking.common.restclient

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "moaje.banking.external-api")
data class BankingExternalApiProperties(
    val baseUrl: String = "http://localhost:8081/",
    val hmacSecret: String = "Moaje-banking-secret",
)
