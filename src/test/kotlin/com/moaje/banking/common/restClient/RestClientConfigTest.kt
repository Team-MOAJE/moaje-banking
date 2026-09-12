package com.moaje.banking.common.restclient

import com.moaje.banking.common.restclient.BankingExternalApiProperties
import com.moaje.banking.common.restclient.RestClientConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.test.util.ReflectionTestUtils

@DisplayName("Mock Banking RestClient 시간 제한 설정")
class RestClientConfigTest {
    private val config = RestClientConfig()

    @Nested
    @DisplayName("유효한 시간 제한이면")
    inner class ValidTimeout {
        @Test
        @DisplayName("connectTimeout과 readTimeout을 실제 HTTP 요청 팩토리에 적용한다")
        fun appliesTimeoutsToRequestFactory() {
            // given: 두 장애 구간을 구분할 수 있도록 서로 다른 시간 제한을 준비한다.
            val properties = BankingExternalApiProperties(
                connectTimeoutMs = 1_234,
                readTimeoutMs = 5_678,
            )

            // when: Mock Banking 전용 HTTP 요청 팩토리를 생성한다.
            val requestFactory = config.mockBankingClientHttpRequestFactory(properties)

            // then: 설정값이 실제 URLConnection에 전달될 팩토리 필드에 각각 보존된다.
            assertThat(requestFactory).isInstanceOf(SimpleClientHttpRequestFactory::class.java)
            assertThat(ReflectionTestUtils.getField(requestFactory, "connectTimeout")).isEqualTo(1_234)
            assertThat(ReflectionTestUtils.getField(requestFactory, "readTimeout")).isEqualTo(5_678)
        }
    }

    @Nested
    @DisplayName("잘못된 시간 제한이면")
    inner class InvalidTimeout {
        @Test
        @DisplayName("무한 대기가 되는 0 밀리초 설정을 거부한다")
        fun rejectsZeroTimeout() {
            // given: URLConnection에서 무한 대기를 뜻하는 0을 connectTimeout으로 지정한다.
            val properties = BankingExternalApiProperties(connectTimeoutMs = 0)

            // when & then: PROCESSING이 무기한 남을 수 있는 설정은 팩토리 생성 전에 차단한다.
            assertThatIllegalArgumentException()
                .isThrownBy { config.mockBankingClientHttpRequestFactory(properties) }
                .withMessageContaining("connect-timeout-ms")
        }
    }
}
