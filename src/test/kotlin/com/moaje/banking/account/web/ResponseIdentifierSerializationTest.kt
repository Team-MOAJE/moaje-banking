package com.moaje.banking.account.web

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.moaje.banking.account.application.model.OpenAccountResult
import com.moaje.banking.transfer.application.model.TransferRequestResult
import com.moaje.banking.transfer.application.model.TransferStatus
import com.moaje.banking.transfer.web.TransferRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

class ResponseIdentifierSerializationTest {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val id = 123456789012345679L

    @Test
    @DisplayName("계좌와 송금 응답의 ID는 문자열이며 금액은 숫자이다")
    fun responseIdentifiersAreStrings() {
        // given: JavaScript Number가 정확히 표현할 수 없는 큰 정수이다.
        val account = OpenAccountResult(id, "owner", "088", "test", 1000, "ACTIVE", Instant.EPOCH, null, accountNumber = "12345678901234")
        val transfer = TransferRequestResult(id, "public-id", TransferStatus.COMPLETED, "완료")
        // when
        val accountJson = mapper.readTree(mapper.writeValueAsString(account))
        val transferJson = mapper.readTree(mapper.writeValueAsString(transfer))
        // then: ID와 달리 잔액까지 문자열로 바꾸지 않는다.
        assertThat(accountJson["accountId"].isTextual).isTrue()
        assertThat(accountJson["accountId"].asText()).isEqualTo(id.toString())
        assertThat(accountJson["balance"].isNumber).isTrue()
        assertThat(accountJson["accountNumber"].asText()).isEqualTo("12345678901234")
        assertThat(accountJson["maskedAccountNumber"].asText()).isEqualTo("**********1234")
        assertThat(transferJson["transferId"].isTextual).isTrue()
        assertThat(transferJson["transferId"].asText()).isEqualTo(id.toString())
    }

    @Test
    @DisplayName("앱이 응답 ID를 문자열로 재전송해도 송금 요청의 Long으로 정확히 읽는다")
    fun acceptsStringIdentifierInTransferRequest() {
        // given / when: 앱에서 Number로 변환하지 않고 원래 문자열을 보낸다.
        val request = mapper.readValue("""{"withdrawalAccountId":"$id","depositBankCode":"088","depositAccountNumber":"test-account","amount":1000}""", TransferRequest::class.java)
        // then
        assertThat(request.withdrawalAccountId).isEqualTo(id)
    }
}
