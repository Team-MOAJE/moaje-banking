package com.moaje.banking.common.restclient

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

@RestClientTest(MockBankingApiClient::class)
@Import(RestClientConfig::class)
@EnableConfigurationProperties(BankingExternalApiProperties::class)
@TestPropertySource(properties = ["moaje.banking.external-api.base-url=http://localhost:8081/"])
class MockBankingApiClientTest(
    @Autowired private val mockBankingApiClient: MockBankingApiClient,
    @Autowired private val server: MockRestServiceServer,
) {
    @MockBean
    private lateinit var redisTemplate: RedisTemplate<String, Any>

    @Test
    @DisplayName("fetches oauth token")
    fun getAuthTokenByMockBankingApiServer() {
        server.expect(requestTo("http://localhost:8081/oauth/2.0/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""{"ci":"CI-12345","userName":"KimYongHwan","phoneNumber":"01040477823"}"""))
            .andRespond(
                withSuccess(
                    """{"accessToken":"test-access-token","tokenType":"Bearer","expiresIn":3600}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val fetchNewToken = mockBankingApiClient.fetchNewToken("CI-12345", "KimYongHwan", "01040477823")
        println(fetchNewToken)

        assertThat(fetchNewToken.accessToken).isEqualTo("test-access-token")
        assertThat(fetchNewToken.tokenType).isEqualTo("Bearer")
        assertThat(fetchNewToken.expiresIn).isEqualTo(3600)
    }
}
