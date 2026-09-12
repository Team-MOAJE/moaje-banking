package com.moaje.banking.common.restclient

import java.time.Duration
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

@Configuration
class RestClientConfig {
    @Bean("mockBankingClientHttpRequestFactory")
    fun mockBankingClientHttpRequestFactory(
        properties: BankingExternalApiProperties,
    ): ClientHttpRequestFactory {
        require(properties.connectTimeoutMs in 1..Int.MAX_VALUE.toLong()) {
            "moaje.banking.external-api.connect-timeout-ms는 1 이상 ${Int.MAX_VALUE} 이하여야 합니다."
        }
        require(properties.readTimeoutMs in 1..Int.MAX_VALUE.toLong()) {
            "moaje.banking.external-api.read-timeout-ms는 1 이상 ${Int.MAX_VALUE} 이하여야 합니다."
        }

        // 연결 제한은 외부 서버까지 통신 경로를 만드는 시간을, 읽기 제한은 요청 전송 후 응답을 기다리는 시간을 제한한다.
        // 두 시간을 명시해야 네트워크 장애가 발생해도 PROCESSING 거래가 무기한 남지 않고 UNKNOWN 대사 흐름으로 진입한다.
        return SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs))
            setReadTimeout(Duration.ofMillis(properties.readTimeoutMs))
        }
    }

    @Bean
    fun mockBankingRestClient(
        builder: RestClient.Builder,
        properties: BankingExternalApiProperties,
        @Qualifier("mockBankingClientHttpRequestFactory") requestFactory: ClientHttpRequestFactory,
    ): RestClient {
        return builder
            .baseUrl(properties.baseUrl)
            .requestFactory(requestFactory)
            .build()
    }
}
