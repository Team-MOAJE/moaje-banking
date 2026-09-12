package com.moaje.banking.controller.transfer

import com.fasterxml.jackson.databind.ObjectMapper
import com.moaje.banking.application.service.TransferCommandService
import com.moaje.banking.domain.transfer.TransferCommand
import com.moaje.banking.domain.transfer.TransferRequestResult
import com.moaje.banking.domain.transfer.TransferStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.security.Principal

class TransferControllerIdempotencyTest {
    private val objectMapper = ObjectMapper().findAndRegisterModules()

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

    private fun mockMvc(service: TransferCommandService) =
        MockMvcBuilders.standaloneSetup(TransferController(service))
            .setControllerAdvice(TransferExceptionHandler())
            .build()

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
