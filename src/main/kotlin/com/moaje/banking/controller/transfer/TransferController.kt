package com.moaje.banking.controller.transfer

import com.moaje.banking.application.service.TransferCommandService
import com.moaje.banking.domain.common.Money
import com.moaje.banking.domain.transfer.TransferCommand
import com.moaje.banking.domain.transfer.TransferRequestResult
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

@RestController
@RequestMapping("/api/v1/banking/transfers")
class TransferController(
    private val transferCommandService: TransferCommandService,
) {
    @PostMapping
    fun transfer(
        @Valid @RequestBody request: TransferRequest,
    ): TransferRequestResult {
        return transferCommandService.request(request.toCommand())
    }
}

data class TransferRequest(
    @field:NotBlank
    val ci: String,
    @field:NotBlank
    val userName: String,
    @field:NotBlank
    val phoneNumber: String,
    @field:NotBlank
    val requesterUserId: String,
    @field:NotBlank
    val withdrawalAccountId: String,
    @field:NotBlank
    val depositBankCode: String,
    @field:NotBlank
    val depositAccountNumber: String,
    @field:Positive
    val amount: Long,
    val currency: String = "KRW",
    val idempotencyKey: String? = null,
) {
    fun toCommand(): TransferCommand {
        return TransferCommand(
            idempotencyKey = idempotencyKey?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
            ci = ci,
            userName = userName,
            phoneNumber = phoneNumber,
            requesterUserId = requesterUserId,
            withdrawalAccountId = withdrawalAccountId,
            depositBankCode = depositBankCode,
            depositAccountNumber = depositAccountNumber,
            amount = Money(amount = BigDecimal.valueOf(amount), currency = currency),
        )
    }
}
