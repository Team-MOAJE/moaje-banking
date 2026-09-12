package com.moaje.banking.controller.transfer

import com.moaje.banking.domain.transfer.IdempotencyKeyConflictException
import com.moaje.banking.domain.transfer.MissingIdempotencyKeyException
import com.moaje.banking.domain.transfer.UnauthenticatedPrincipalException
import com.moaje.banking.domain.account.BankingAccountNotActiveException
import com.moaje.banking.domain.account.BankingAccountNotFoundException
import com.moaje.banking.domain.account.BankingAccountOwnershipException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

@RestControllerAdvice
class TransferExceptionHandler {
    @ExceptionHandler(UnauthenticatedPrincipalException::class)
    fun handleUnauthenticated(exception: UnauthenticatedPrincipalException): ResponseEntity<ApiErrorResponse> {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", exception.message ?: "Authenticated principal is required.")
    }

    @ExceptionHandler(MissingIdempotencyKeyException::class)
    fun handleMissingIdempotencyKey(exception: MissingIdempotencyKeyException): ResponseEntity<ApiErrorResponse> {
        return error(HttpStatus.BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY", exception.message ?: "Idempotency-Key header is required.")
    }

    @ExceptionHandler(IdempotencyKeyConflictException::class)
    fun handleIdempotencyConflict(exception: IdempotencyKeyConflictException): ResponseEntity<ApiErrorResponse> {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", exception.message ?: "Idempotency-Key conflict.")
    }

    @ExceptionHandler(BankingAccountNotFoundException::class)
    fun handleAccountNotFound(exception: BankingAccountNotFoundException): ResponseEntity<ApiErrorResponse> {
        return error(HttpStatus.NOT_FOUND, "BANKING_ACCOUNT_NOT_FOUND", exception.message ?: "Banking account not found.")
    }

    @ExceptionHandler(BankingAccountOwnershipException::class)
    fun handleAccountOwnership(exception: BankingAccountOwnershipException): ResponseEntity<ApiErrorResponse> {
        return error(HttpStatus.FORBIDDEN, "BANKING_ACCOUNT_FORBIDDEN", exception.message ?: "Banking account access denied.")
    }

    @ExceptionHandler(BankingAccountNotActiveException::class)
    fun handleAccountNotActive(exception: BankingAccountNotActiveException): ResponseEntity<ApiErrorResponse> {
        return error(HttpStatus.CONFLICT, "BANKING_ACCOUNT_NOT_ACTIVE", exception.message ?: "Banking account is not active.")
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(exception: IllegalArgumentException): ResponseEntity<ApiErrorResponse> {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.message ?: "Invalid request.")
    }

    private fun error(
        status: HttpStatus,
        code: String,
        message: String,
    ): ResponseEntity<ApiErrorResponse> {
        return ResponseEntity.status(status)
            .body(
                ApiErrorResponse(
                    code = code,
                    message = message,
                    timestamp = Instant.now().toString(),
                ),
            )
    }
}

data class ApiErrorResponse(
    val code: String,
    val message: String,
    val timestamp: String,
)
