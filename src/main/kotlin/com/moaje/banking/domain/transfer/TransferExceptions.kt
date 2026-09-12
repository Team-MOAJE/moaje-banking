package com.moaje.banking.domain.transfer

class MissingIdempotencyKeyException : RuntimeException("Idempotency-Key header is required.")

class IdempotencyKeyConflictException : RuntimeException("Idempotency-Key was reused with a different transfer request.")

class UnauthenticatedPrincipalException : RuntimeException("Authenticated principal is required.")

class InvalidTransferStateTransitionException(
    from: BankingTransferStatus,
    to: BankingTransferStatus,
) : RuntimeException("허용되지 않는 송금 상태 전이입니다. $from -> $to")
