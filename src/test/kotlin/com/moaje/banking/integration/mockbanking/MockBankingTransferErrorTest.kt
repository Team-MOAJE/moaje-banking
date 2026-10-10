package com.moaje.banking.integration.mockbanking

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.moaje.banking.common.domain.Money
import com.moaje.banking.kftc.domain.KftcApiLogStatus
import com.moaje.banking.transfer.domain.PrincipalId
import com.moaje.banking.transfer.domain.TransferCommand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.net.SocketTimeoutException
import java.util.stream.Stream

class MockBankingTransferErrorTest {
    private val builder = RestClient.builder().baseUrl("http://mock.test/")
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val redis = Mockito.mock(StringRedisTemplate::class.java)
    private val client: MockBankingApiClient

    init {
        @Suppress("UNCHECKED_CAST")
        val values = Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
        Mockito.`when`(redis.opsForValue()).thenReturn(values)
        client = MockBankingApiClient(builder.build(), jacksonObjectMapper(), redis,
            BankingExternalApiProperties(hmacSecret = "test-only-signing-value"))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("httpErrors")
    @DisplayName("명확한 HTTP 상태와 거절 코드 조합만 FAILED로 처리한다")
    fun classifiesHttpError(case: HttpErrorCase) {
        // given: Mock의 오류 본문에 부가 필드나 잘못된 값이 있어도 결과를 안전하게 분류해야 한다.
        expectToken()
        server.expect(requestTo("http://mock.test/api/transfers"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(case.httpStatus).contentType(MediaType.APPLICATION_JSON).body(case.body))

        // when
        val result = client.transferRequest(100L, command(), "test-provider")

        // then: 읽을 수 없는 응답이나 미확정 HTTP 오류를 금융 실패로 확정하지 않는다.
        assertThat(result.status).isEqualTo(case.expected)
        assertThat(result.responseCode).isEqualTo(case.httpStatus.value().toString())
        assertThat(result.failureReason).doesNotContain("private-response-marker")
        assertThat(result.requestPayload).doesNotContain("test-recipient")
        server.verify()
    }

    @Test
    @DisplayName("HTTP 통신 Timeout은 실패로 확정하지 않는다")
    fun timeoutRemainsUncertain() {
        // given: 실제 계정계의 실행 여부는 이 예외만으로 알 수 없다.
        expectToken()
        server.expect(requestTo("http://mock.test/api/transfers"))
            .andRespond(withException(SocketTimeoutException("test timeout")))

        // when
        val result = client.transferRequest(100L, command(), "test-provider")

        // then
        assertThat(result.status).isEqualTo(KftcApiLogStatus.TIMEOUT)
        server.verify()
    }

    @Test
    @DisplayName("성공 HTTP 응답의 본문을 해석하지 못해도 FAILED로 확정하지 않는다")
    fun unreadableSuccessBodyRemainsUncertain() {
        // given: 통합 시험에서 관찰한 응답 추출 오류를 HTTP Client 수준에서 재현한다.
        expectToken()
        server.expect(requestTo("http://mock.test/api/transfers"))
            .andRespond(withSuccess("private-response-marker", MediaType.APPLICATION_OCTET_STREAM))

        // when
        val result = client.transferRequest(100L, command(), "test-provider")

        // then: 이 결과는 Application에서 UNKNOWN으로 저장되어야 한다.
        assertThat(result.status).isEqualTo(KftcApiLogStatus.UNREADABLE_RESPONSE)
        assertThat(result.failureReason).doesNotContain("private-response-marker")
        server.verify()
    }

    private fun expectToken() {
        server.expect(requestTo("http://mock.test/oauth/2.0/token"))
            .andRespond(withSuccess(
                """{"accessToken":"test-only-token","tokenType":"Bearer","expiresIn":3600}""",
                MediaType.APPLICATION_JSON,
            ))
    }

    private fun command() = TransferCommand(PrincipalId("test-user"), "test-key", 1L,
        "test-bank", "test-recipient", Money.krw(100))

    data class HttpErrorCase(
        val description: String,
        val httpStatus: HttpStatus,
        val body: String,
        val expected: KftcApiLogStatus,
    ) {
        override fun toString(): String = description
    }

    companion object {
        @JvmStatic
        fun httpErrors(): Stream<HttpErrorCase> = Stream.of(
            rejection("계좌 없음", HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND"),
            rejection("계좌 해지", HttpStatus.BAD_REQUEST, "ACCOUNT_CANCELED"),
            rejection("잔액 부족", HttpStatus.BAD_REQUEST, "INSUFFICIENT_BALANCE"),
            rejection("금액 오류", HttpStatus.BAD_REQUEST, "INVALID_ACCOUNT_AMOUNT"),
            rejection("동일 계좌", HttpStatus.BAD_REQUEST, "SAME_ACCOUNT_TRANSFER"),
            HttpErrorCase("상태 불일치", HttpStatus.INTERNAL_SERVER_ERROR,
                """{"code":"INSUFFICIENT_BALANCE"}""", KftcApiLogStatus.UNCONFIRMED_HTTP_ERROR),
            HttpErrorCase("미등록 코드", HttpStatus.BAD_REQUEST,
                """{"code":"ACCOUNT_BUSINESS_ERROR"}""", KftcApiLogStatus.UNCONFIRMED_HTTP_ERROR),
            HttpErrorCase("조회 전용 코드", HttpStatus.NOT_FOUND,
                """{"code":"TRANSFER_NOT_FOUND"}""", KftcApiLogStatus.UNCONFIRMED_HTTP_ERROR),
            HttpErrorCase("인증 오류", HttpStatus.UNAUTHORIZED,
                """{"code":"UNAUTHORIZED"}""", KftcApiLogStatus.UNCONFIRMED_HTTP_ERROR),
            HttpErrorCase("빈 본문", HttpStatus.INTERNAL_SERVER_ERROR, "", KftcApiLogStatus.UNREADABLE_RESPONSE),
            HttpErrorCase("손상 본문", HttpStatus.BAD_GATEWAY, "private-response-marker", KftcApiLogStatus.UNREADABLE_RESPONSE),
            HttpErrorCase("코드 누락", HttpStatus.BAD_REQUEST, "{}", KftcApiLogStatus.UNREADABLE_RESPONSE),
            HttpErrorCase("null 본문", HttpStatus.INTERNAL_SERVER_ERROR, "null", KftcApiLogStatus.UNREADABLE_RESPONSE),
        )

        private fun rejection(description: String, status: HttpStatus, code: String) = HttpErrorCase(
            description, status,
            """{"code":"$code","message":"private-response-marker","timestamp":"2026-10-10T00:00:00Z"}""",
            KftcApiLogStatus.FAILED,
        )
    }
}
