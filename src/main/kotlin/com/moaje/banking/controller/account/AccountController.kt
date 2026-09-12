package com.moaje.banking.controller.account

import com.moaje.banking.application.service.AccountOpeningService
import com.moaje.banking.domain.account.OpenAccountCommand
import com.moaje.banking.domain.account.OpenAccountResult
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/banking/accounts")
class AccountController(
    private val accountOpeningService: AccountOpeningService,
) {
    /**
     * 클라이언트의 계좌개설 요청을 받아 목업 뱅킹 계좌개설을 진행합니다.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun openAccount(
        @Valid @RequestBody request: OpenAccountRequest,
        principal: java.security.Principal,
    ): OpenAccountResult {
        return accountOpeningService.open(request.toCommand(principal.name))
    }
}

data class OpenAccountRequest(
    // 구버전 요청 호환용 필드이며 계좌 소유자를 정하는 데 사용하지 않는다.
    val userId: String? = null,
    @field:NotBlank
    val ci: String,
    @field:NotBlank
    val userName: String,
    @field:NotBlank
    val phoneNumber: String,
    @field:NotBlank
    val bankCode: String,
    @field:NotBlank
    val productName: String,
    @field:Min(0)
    val initialBalance: Long,
) {
    fun toCommand(principalId: String): OpenAccountCommand {
        return OpenAccountCommand(
            userId = principalId,
            ci = ci,
            userName = userName,
            phoneNumber = phoneNumber,
            bankCode = bankCode,
            productName = productName,
            initialBalance = initialBalance,
        )
    }
}
