package com.moaje.banking.transfer.web

import com.moaje.banking.common.auth.AuthUserId
import com.moaje.banking.transfer.application.service.TransferCommandService
import com.moaje.banking.common.domain.Money
import com.moaje.banking.transfer.domain.PrincipalId
import com.moaje.banking.transfer.domain.TransferCommand
import com.moaje.banking.transfer.application.model.TransferRequestResult
import com.moaje.banking.transfer.support.MissingIdempotencyKeyException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import io.swagger.v3.oas.annotations.media.Schema

@RestController
@RequestMapping("/api/v1/banking/transfers")
class TransferController(
    private val transferCommandService: TransferCommandService,
) {
    @PostMapping
    fun transfer(
        @AuthUserId(required = true) userId: String,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody request: TransferRequest,
    ): TransferRequestResult {
        // 인증 경계에서는 문자열로 받고, 업무 계층에는 전용 사용자 ID 타입을 전달한다.
        val principalId = PrincipalId(userId)
        return transferCommandService.request(request.toCommand(principalId, idempotencyKey.toIdempotencyKey()))
    }


    private fun String?.toIdempotencyKey(): String {
        return this?.takeIf { it.isNotBlank() } ?: throw MissingIdempotencyKeyException()
    }
}

data class TransferRequest(
    // 계좌번호는 표시·수취용이다. 출금은 JWT 소유권을 검증할 수 있는 내부 accountId로만 요청한다.
    @field:Schema(type = "string", description = "계좌 목록의 본인 accountId. accountNumber를 넣지 않는다.", example = "123456789012345678")
    @field:Positive
    val withdrawalAccountId: Long,
    @field:NotBlank
    val depositBankCode: String,
    @field:NotBlank
    val depositAccountNumber: String,
    @field:Positive
    val amount: Long,
    val currency: String = "KRW",
) {
    fun toCommand(principalId: PrincipalId, idempotencyKey: String): TransferCommand {
        return TransferCommand(
            principalId = principalId,
            idempotencyKey = idempotencyKey,
            withdrawalAccountId = withdrawalAccountId,
            depositBankCode = depositBankCode,
            depositAccountNumber = depositAccountNumber,
            amount = Money(amount = BigDecimal.valueOf(amount), currency = currency),
        )
    }
}
