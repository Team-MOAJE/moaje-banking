package com.moaje.banking.transfer.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.moaje.banking.common.auth.AuthUserIdArgumentResolver
import com.moaje.banking.common.config.WebConfig
import com.moaje.banking.transfer.application.service.TransferCommandService
import com.moaje.banking.transfer.domain.TransferCommand
import com.moaje.banking.transfer.application.model.TransferRequestResult
import com.moaje.banking.transfer.application.model.TransferStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import java.security.Principal

class TransferControllerIdempotencyTest {
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    @Test
    @DisplayName("출금 계좌번호만 보내고 withdrawalAccountId를 생략하면 400으로 거절한다")
    fun rejectsAccountNumberInsteadOfWithdrawalAccountId() {
        // given: 표시용 계좌번호를 업무용 ID 대신 보내는 잘못된 요청이다.
        val service = CapturingTransferCommandService()
        val body = objectMapper.readTree(transferBody("user-1")) as com.fasterxml.jackson.databind.node.ObjectNode
        body.remove("withdrawalAccountId")
        body.put("withdrawalAccountNumber", "12345678901234")
        // when
        val response = mockMvc(service).perform(post("/api/v1/banking/transfers")
            .principal(Principal { "user-1" }).header("Idempotency-Key", "account-id-required")
            .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn().response
        // then: 계좌번호로 우회 조회하거나 송금을 실행하지 않는다.
        assertThat(response.status).isEqualTo(400)
        assertThat(service.capturedCommand).isNull()
    }

    @Test
    @DisplayName("문자열 withdrawalAccountId로 요청하면 계좌번호가 아니라 그 ID를 업무 계층에 전달한다")
    fun forwardsStringWithdrawalAccountId() {
        // given
        val service = CapturingTransferCommandService()
        val body = objectMapper.readTree(transferBody("user-1")) as com.fasterxml.jackson.databind.node.ObjectNode
        body.put("withdrawalAccountId", "123456789012345679")
        body.put("withdrawalAccountNumber", "ignored-display-number")
        // when
        val response = mockMvc(service).perform(post("/api/v1/banking/transfers")
            .principal(Principal { "user-1" }).header("Idempotency-Key", "string-account-id")
            .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn().response
        // then
        assertThat(response.status).isEqualTo(200)
        assertThat(service.capturedCommand?.withdrawalAccountId).isEqualTo(123456789012345679L)
    }

    @Test
    @DisplayName("Idempotency-Key 누락 시 4xx가 반환되고 거래가 생성되지 않는다")
    fun missingIdempotencyKeyReturns4xxAndDoesNotCallApplicationService() {
        // given
        val service = CapturingTransferCommandService()
        val mockMvc = mockMvc(service)

        // when
        val response = mockMvc.perform(
            post("/api/v1/banking/transfers")
                .principal(Principal { "user-1" })
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferBody(requesterUserId = "attacker")),
        ).andReturn().response

        // then
        assertThat(response.status).isEqualTo(400)
        assertThat(response.contentAsString).contains("MISSING_IDEMPOTENCY_KEY")
        assertThat(service.capturedCommand).isNull()
    }

    @Test
    @DisplayName("인증 Principal이 없으면 Request Body의 requesterUserId로 fallback하지 않고 401을 반환한다")
    fun missingPrincipalReturns401WithoutRequesterUserIdFallback() {
        // given
        val service = CapturingTransferCommandService()
        val mockMvc = mockMvc(service)

        // when
        val response = mockMvc.perform(
            post("/api/v1/banking/transfers")
                .header("Idempotency-Key", "idem-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferBody(requesterUserId = "attacker")),
        ).andReturn().response

        // then
        assertThat(response.status).isEqualTo(401)
        assertThat(response.contentAsString).contains("UNAUTHENTICATED")
        assertThat(service.capturedCommand).isNull()
    }

    @Test
    @DisplayName("Request Body의 requesterUserId를 바꿔도 인증 Principal이 Application Command의 principalId가 된다")
    fun requesterUserIdBodyFieldIsNotTrustedAsPrincipal() {
        // given
        val service = CapturingTransferCommandService()
        val mockMvc = mockMvc(service)

        // when
        val response = mockMvc.perform(
            post("/api/v1/banking/transfers")
                .principal(Principal { "user-1" })
                .header("Idempotency-Key", "idem-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferBody(requesterUserId = "attacker")),
        ).andReturn().response

        // then
        assertThat(response.status).isEqualTo(200)
        assertThat(service.capturedCommand?.principalId?.value).isEqualTo("user-1")
        assertThat(service.capturedCommand?.idempotencyKey).isEqualTo("idem-1")
    }

    private fun mockMvc(service: TransferCommandService): org.springframework.test.web.servlet.MockMvc {
        // standaloneSetup은 WebConfig를 자동으로 읽지 않으므로 실제 설정의 리졸버를 직접 등록한다.
        // 그래야 PrincipalId 변환과 인증 누락 시 401 응답을 실제 Controller 경로로 검증할 수 있다.
        val resolvers = mutableListOf<HandlerMethodArgumentResolver?>()
        WebConfig(AuthUserIdArgumentResolver()).addArgumentResolvers(resolvers)
        return MockMvcBuilders.standaloneSetup(TransferController(service))
            .setCustomArgumentResolvers(*resolvers.filterNotNull().toTypedArray())
            .setControllerAdvice(TransferExceptionHandler())
            .build()
    }

    private fun transferBody(requesterUserId: String): String {
        return objectMapper.writeValueAsString(
            mapOf(
                "requesterUserId" to requesterUserId,
                "withdrawalAccountId" to 1L,
                "depositBankCode" to "088",
                "depositAccountNumber" to "deposit-account",
                "amount" to 10_000,
                "currency" to "KRW",
            ),
        )
    }

    private class CapturingTransferCommandService : TransferCommandService {
        var capturedCommand: TransferCommand? = null

        override fun request(command: TransferCommand): TransferRequestResult {
            capturedCommand = command
            return TransferRequestResult(
                transferId = 100L,
                publicTransferId = "MOAJE-BNK-100",
                status = TransferStatus.PROCESSING,
                message = "ok",
            )
        }
    }
}
