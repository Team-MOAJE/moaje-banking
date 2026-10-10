package com.moaje.banking.common.auth

import com.moaje.banking.common.config.WebConfig
import com.moaje.banking.common.domain.Money
import com.moaje.banking.transfer.application.model.TransferRequestResult
import com.moaje.banking.transfer.application.model.TransferStatus
import com.moaje.banking.transfer.application.service.TransferCommandService
import com.moaje.banking.transfer.domain.TransferCommand
import com.moaje.banking.transfer.domain.PrincipalId
import com.moaje.banking.transfer.web.TransferController
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.refEq
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal

@WebMvcTest(TransferController::class)
@Import(WebConfig::class, AuthUserIdArgumentResolver::class)
class AuthUserIdMvcIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var transferCommandService: TransferCommandService

    @Test
    @DisplayName("Gateway 사용자 헤더가 Filter와 등록된 Resolver를 거쳐 PrincipalId로 전달된다")
    fun authenticatedHeaderReachesApplicationService() {
        // given: Controller만 직접 호출하지 않고 실제 MVC 설정과 인증 Filter를 함께 사용한다.
        val command = TransferCommand(
            principalId = PrincipalId("123"),
            idempotencyKey = "mvc-test-key",
            withdrawalAccountId = 1L,
            depositBankCode = "TEST",
            depositAccountNumber = "test-destination",
            amount = Money(BigDecimal("1000")),
        )
        // 요청 생성 시각은 매번 달라지므로 제외하고 사용자 ID와 송금 입력값 전체를 비교한다.
        // Mockito 매처의 null 반환값은 Kotlin의 non-null 파라미터에 맞게 보완한다.
        `when`(transferCommandService.request(refEq(command, "requestedAt") ?: command)).thenReturn(
            TransferRequestResult(100L, "MOAJE-BNK-100", TransferStatus.PROCESSING, "ok"),
        )

        // when & then: 본문의 사용자 ID 대신 Gateway가 전달한 사용자 ID를 사용한다.
        mockMvc.perform(
            post("/api/v1/banking/transfers")
                .header("X-Authenticated-User-Id", "123")
                .header("Idempotency-Key", "mvc-test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.transferId").value(100))
        verify(transferCommandService).request(refEq(command, "requestedAt") ?: command)
    }

    @Test
    @DisplayName("Gateway 사용자 헤더가 없으면 송금 업무를 호출하지 않고 401을 반환한다")
    fun missingAuthenticationDoesNotCallApplicationService() {
        // given & when: 본문에 사용자 ID가 있어도 인증 헤더를 대신할 수 없다.
        mockMvc.perform(
            post("/api/v1/banking/transfers")
                .header("Idempotency-Key", "mvc-test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isUnauthorized)

        // then: 인증되지 않은 요청은 금융 업무에 진입하지 않는다.
        verifyNoInteractions(transferCommandService)
    }

    private val body = """
        {"requesterUserId":"untrusted","withdrawalAccountId":1,
         "depositBankCode":"TEST","depositAccountNumber":"test-destination","amount":1000}
    """.trimIndent()
}
