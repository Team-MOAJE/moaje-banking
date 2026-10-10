package com.moaje.banking.transfer.support

import com.moaje.banking.account.domain.BankingAccountStatus
import com.moaje.banking.transfer.domain.BankingTransferStatus

open class DomainException(message: String) : RuntimeException(message)


class BankingAccountNotFoundException(accountId: Long) :
    DomainException("계좌를 찾을 수 없습니다. accountId=$accountId")

class BankingAccountOwnershipException :
    DomainException("요청 사용자는 출금 계좌의 소유자가 아닙니다.")

class BankingAccountNotActiveException(status: BankingAccountStatus) :
    DomainException("활성 상태의 계좌만 송금할 수 있습니다. status=$status")


class MissingIdempotencyKeyException :
    DomainException("멱등성 헤더 키가 누락되었습니다. ")

class IdempotencyKeyConflictException :
    DomainException("다른 종류의 요청으로 인해 멱등성 키가 충돌했습니다. ")

class UnauthenticatedPrincipalException :
    DomainException("인증된 유저가 아닙니다. ")

class InvalidTransferStateTransitionException(
    from: BankingTransferStatus,
    to: BankingTransferStatus,
) : DomainException("허용되지 않는 송금 상태 전이입니다. $from -> $to")
