package com.moaje.banking.common.restclient

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class RestClientConfig {
    @Bean
    fun mockBankingRestClient(
        builder: RestClient.Builder,
        properties: BankingExternalApiProperties,
    ): RestClient {
        return builder
            .baseUrl(properties.baseUrl)
            .build()
    }
}
