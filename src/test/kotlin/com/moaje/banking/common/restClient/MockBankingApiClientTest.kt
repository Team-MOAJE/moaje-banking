package com.moaje.banking.common.restclient

import com.moaje.banking.domain.common.Money
import com.moaje.banking.domain.transfer.PrincipalId
import com.moaje.banking.domain.transfer.TransferCommand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.mockito.Mockito
import java.math.BigDecimal

@RestClientTest(MockBankingApiClient::class)
@Import(MockBankingApiClientTestConfig::class)
@EnableConfigurationProperties(BankingExternalApiProperties::class)
@TestPropertySource(properties = ["moaje.banking.external-api.base-url=http://localhost:8081/"])
class MockBankingApiClientTest(
    @Autowired private val mockBankingApiClient: MockBankingApiClient,
    @Autowired private val server: MockRestServiceServer,
) {
    @MockBean
    private lateinit var redisTemplate: StringRedisTemplate

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

    @Test
    @DisplayName("송금 요청은 내부 accountId나 실제 출금 계좌번호 대신 providerAccountId를 전달한다")
    fun transferRequestUsesProviderAccountIdAtExternalBoundary() {
        // given: 토큰 캐시는 비어 있고 Mock Banking 토큰 발급과 송금 응답을 순서대로 준비한다.
        @Suppress("UNCHECKED_CAST")
        val valueOperations = Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
        Mockito.`when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        Mockito.`when`(valueOperations.get(Mockito.anyString())).thenReturn(null)
        server.expect(requestTo("http://localhost:8081/oauth/2.0/token"))
            .andRespond(
                withSuccess(
                    """{"accessToken":"test-access-token","tokenType":"Bearer","expiresIn":3600}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        server.expect(requestTo("http://localhost:8081/api/transfers"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().string(
                    """{"clientTransferId":"100","fromProviderAccountId":"provider-opaque-1","toAccountNumber":"destination-account","amount":10000}""",
                ),
            )
            .andRespond(
                withSuccess(
                    """{"clientTransferId":"100","fromAccountNumber":"masked-source","toAccountNumber":"masked-destination","amount":10000,"fromBalance":90000,"toBalance":110000,"debitHistory":{"transactionId":"external-debit-1","accountNumber":"masked-source","type":"TRANSFER_OUT","amount":10000,"counterpartyAccountNumber":null,"counterpartyBankCode":null,"memo":null,"createdAt":"2026-01-01T00:00:00Z"},"creditHistory":{"transactionId":"external-credit-1","accountNumber":"masked-destination","type":"TRANSFER_IN","amount":10000,"counterpartyAccountNumber":null,"counterpartyBankCode":null,"memo":null,"createdAt":"2026-01-01T00:00:00Z"}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        // when
        val result = mockBankingApiClient.transferRequest(
            transferId = 100L,
            command = TransferCommand(
                principalId = PrincipalId("user-1"),
                idempotencyKey = "idem-1",
                withdrawalAccountId = 1L,
                depositBankCode = "088",
                depositAccountNumber = "destination-account",
                amount = Money(BigDecimal.valueOf(10_000)),
            ),
            withdrawalProviderAccountId = "provider-opaque-1",
        )

        // then: Banking에는 Mock 원장의 자체 거래 ID만 결과로 남는다.
        assertThat(result.externalTransactionId).isEqualTo("external-debit-1")
        assertThat(result.requestPayload).contains("\"clientTransferId\":\"100\"")
        assertThat(result.requestPayload).doesNotContain("provider-opaque-1", "destination-account")
        server.verify()
    }
}

@TestConfiguration
class MockBankingApiClientTestConfig {
    @Bean
    fun mockBankingRestClient(
        builder: RestClient.Builder,
        properties: BankingExternalApiProperties,
    ): RestClient = builder.baseUrl(properties.baseUrl).build()
}
