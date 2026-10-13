package com.moaje.banking.account.web

import com.moaje.banking.account.application.service.AccountOpeningService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class AccountBankRequestTest {
    @Test
    @DisplayName("은행명 누락 또는 공백은 400으로 거절하고 계좌를 생성하지 않는다")
    fun rejectsMissingOrBlankBankName() {
        // given: 다른 필드는 정상이며 은행명만 빠진 요청이다.
        val service = Mockito.mock(AccountOpeningService::class.java)
        val mvc = MockMvcBuilders.standaloneSetup(AccountController(service)).build()
        val body = """{"ci":"test-ci","userName":"test-user","phoneNumber":"test-phone","bankCode":"088","productName":"test-product","initialBalance":0} """
        // when / then: 요청 검증 단계에서 거절되어 외부 계좌 생성까지 진행하지 않는다.
        for (requestBody in listOf(body, body.trim().dropLast(1) + ",\"bankName\":\"   \"}")) {
            mvc.perform(post("/api/v1/banking/accounts").principal { "owner" }
                .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isBadRequest)
        }
        Mockito.verifyNoInteractions(service)
    }

    @Test
    @DisplayName("은행 코드의 앞자리 0은 보존하고 양끝 공백만 제거한다")
    fun normalizesBankInformationWithoutChangingCode() {
        // given
        val request = OpenAccountRequest(ci = "test-ci", userName = "test-user", phoneNumber = "test-phone",
            bankCode = " 088 ", bankName = " 테스트 은행 ", productName = "test-product", initialBalance = 0)
        // when
        val command = request.toCommand("owner")
        // then
        assertThat(command.bankCode).isEqualTo("088")
        assertThat(command.bankName).isEqualTo("테스트 은행")
    }
}
