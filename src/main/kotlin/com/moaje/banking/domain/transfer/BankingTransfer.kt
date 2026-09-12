package com.moaje.banking.domain.transfer

import com.moaje.banking.common.id.TsidGeneratedValue
import com.moaje.banking.domain.common.Money
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime

@Entity
@Table(
    name = "banking_transfer",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_banking_transfer_principal_operation_idempotency",
            columnNames = ["principal_id", "operation_type", "idempotency_key"],
        ),
    ],
    indexes = [
        Index(name = "idx_banking_transfer_status", columnList = "status"),
        Index(name = "idx_banking_transfer_external_transaction_id", columnList = "external_transaction_id"),
        Index(name = "idx_banking_transfer_external_reversal_transaction_id", columnList = "external_reversal_transaction_id"),
        Index(name = "idx_banking_transfer_withdrawal_account_id", columnList = "withdrawal_account_id"),
        Index(name = "idx_banking_transfer_reconciliation_required", columnList = "reconciliation_required"),
        Index(name = "idx_banking_transfer_reconciliation_scan", columnList = "status,reconciliation_required,processing_started_at,next_reconciliation_at,reconciliation_locked_until"),
    ],
)
class BankingTransfer(
    @Id
    @Column(name = "transfer_id", nullable = false)
    var transferId: Long,

    @Column(name = "principal_id", nullable = false, length = 100)
    var principalId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 40)
    var operationType: OperationType = OperationType.BANKING_TRANSFER,

    @Column(name = "idempotency_key", nullable = false, length = 120)
    var idempotencyKey: String,

    @Column(name = "request_hash", nullable = false, length = 64)
    var requestHash: String,

    @Column(name = "withdrawal_account_id", nullable = false)
    var withdrawalAccountId: Long,

    @Column(name = "deposit_bank_code", nullable = false, length = 20)
    var depositBankCode: String,

    @Column(name = "deposit_account_number", nullable = false, length = 100)
    var depositAccountNumber: String,

    @Column(name = "amount", nullable = false, precision = 18, scale = 4)
    var amount: BigDecimal,

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String,

    @Column(name = "external_transaction_id", length = 100)
    var externalTransactionId: String? = null,

    @Column(name = "external_reversal_transaction_id", length = 100)
    var externalReversalTransactionId: String? = null,

    @Column(name = "reversal_reason", length = 255)
    var reversalReason: String? = null,

    @Column(name = "reversed_at")
    var reversedAt: LocalDateTime? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: BankingTransferStatus = BankingTransferStatus.REQUESTED,

    @Column(name = "failure_code", length = 50)
    var failureCode: String? = null,

    @Column(name = "failure_reason", length = 255)
    var failureReason: String? = null,

    @Column(name = "reconciliation_required", nullable = false)
    var reconciliationRequired: Boolean = false,

    @Column(name = "processing_started_at")
    var processingStartedAt: LocalDateTime? = null,

    @Column(name = "reconciliation_attempt_count", nullable = false)
    var reconciliationAttemptCount: Int = 0,

    @Column(name = "last_reconciliation_failure_reason", length = 500)
    var lastReconciliationFailureReason: String? = null,

    @Column(name = "last_reconciled_at")
    var lastReconciledAt: LocalDateTime? = null,

    @Column(name = "next_reconciliation_at")
    var nextReconciliationAt: LocalDateTime? = null,

    @Column(name = "reconciliation_retry_exhausted", nullable = false)
    var reconciliationRetryExhausted: Boolean = false,

    @Column(name = "reconciliation_locked_at")
    var reconciliationLockedAt: LocalDateTime? = null,

    @Column(name = "reconciliation_locked_until")
    var reconciliationLockedUntil: LocalDateTime? = null,

    @Column(name = "reconciliation_lock_owner", length = 120)
    var reconciliationLockOwner: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
) {

    // 인자로 들어온 RequestHash가 기존 Hash와 충돌하는지 검사한다.
    fun requireSameRequest(requestHash: TransferRequestHash) {
        if (this.requestHash != requestHash.value) {
            throw IdempotencyKeyConflictException()
        }
    }

    // 상태를 PROCESSING으로 변경한다.
    fun markProcessing() {
        transitionTo(BankingTransferStatus.PROCESSING)
        processingStartedAt = LocalDateTime.now()
        reconciliationRequired = false
    }

    // 상태를 SUCCESS로 변경한다.
    fun markSucceeded(externalTransactionId: String?) {
        transitionTo(BankingTransferStatus.SUCCEEDED)
        this.externalTransactionId = externalTransactionId
        failureCode = null
        failureReason = null
        reconciliationRequired = false
        clearReconciliationOperationState()
    }

    // 상태를 FAIL로 변경한다.
    fun markFailed(code: String?, reason: String?) {
        transitionTo(BankingTransferStatus.FAILED)
        failureCode = code
        failureReason = reason
        reconciliationRequired = false
        clearReconciliationOperationState()
    }

    fun markUnknown(code: String?, reason: String?) {
        transitionTo(BankingTransferStatus.UNKNOWN)
        failureCode = code
        failureReason = reason
        reconciliationRequired = true
    }

    /**
     * 대사에서 Mock Banking이 이미 반전 완료됐다고 확정한 경우에만 REVERSED로 전이한다.
     * 원송금 성공 기록과 외부 원거래 ID는 감사 근거이므로 덮어쓰지 않고, 보상 거래 ID와 시각을 별도로 보존한다.
     * 현재 모아제에는 보상을 시작할 업무 정책이 없으므로 이 메서드는 reverse 요청을 의미하지 않는다.
     */
    fun markReversed(
        originalExternalTransactionId: String?,
        externalReversalTransactionId: String?,
        reason: String?,
        reversedAt: LocalDateTime = LocalDateTime.now(),
    ) {
        transitionTo(BankingTransferStatus.REVERSED)
        if (!originalExternalTransactionId.isNullOrBlank()) {
            this.externalTransactionId = originalExternalTransactionId
        }
        this.externalReversalTransactionId = externalReversalTransactionId
        this.reversalReason = reason?.take(255)
        this.reversedAt = reversedAt
        failureCode = null
        failureReason = null
        reconciliationRequired = false
        clearReconciliationOperationState()
    }

    fun isReconciliationLockedBy(owner: String): Boolean = reconciliationLockOwner == owner

    /**
     * 대사 조회가 성공적으로 거래 결과를 확정한 경우 호출한다.
     * Worker 점유 정보와 다음 재시도 예약은 운영 상태이므로, 금융 상태 확정 후 반드시 비워야 한다.
     */
    fun recordReconciliationResolved(resolvedAt: LocalDateTime = LocalDateTime.now()) {
        reconciliationAttemptCount += 1
        lastReconciliationFailureReason = null
        lastReconciledAt = resolvedAt
        clearReconciliationOperationState()
    }

    /**
     * Mock Banking 조회 장애는 원거래 실패가 아니므로 FAILED로 확정하지 않는다.
     * 대신 재시도 횟수와 다음 조회 시각을 남기고, 한도를 넘기면 자동 Worker 대상에서 제외한다.
     */
    fun recordReconciliationLookupFailure(
        code: String?,
        reason: String?,
        now: LocalDateTime = LocalDateTime.now(),
        maxAttempts: Int,
        baseBackoffMillis: Long,
        maxBackoffMillis: Long,
    ) {
        reconciliationAttemptCount += 1
        lastReconciliationFailureReason = reason?.take(500)
        if (status == BankingTransferStatus.PROCESSING) {
            markUnknown(code, reason)
        } else {
            reconciliationRequired = true
            failureCode = code ?: failureCode
            failureReason = reason ?: failureReason
        }

        if (reconciliationAttemptCount >= maxAttempts.coerceAtLeast(1)) {
            reconciliationRetryExhausted = true
            nextReconciliationAt = null
        } else {
            reconciliationRetryExhausted = false
            nextReconciliationAt = now.plus(Duration.ofMillis(calculateReconciliationBackoff(baseBackoffMillis, maxBackoffMillis)))
        }
        clearReconciliationLease()
    }

    // 거래 상태를 변경한다.
    private fun transitionTo(next: BankingTransferStatus) {
        if (next !in allowedNextStatuses()) {
            throw InvalidTransferStateTransitionException(status, next)
        }
        status = next
    }

    // 현재 상태에서 다음 상태로 넘어갈 수 있는 상태의 ENUM 을 set 으로 만들어서 반환한다.
    // 상태 전이에 강제성을 띄게 하여 발생가능한 변수상황을 줄이는 목적
    private fun allowedNextStatuses(): Set<BankingTransferStatus> {
        return when (status) {
            BankingTransferStatus.REQUESTED -> setOf(BankingTransferStatus.PROCESSING)
            BankingTransferStatus.PROCESSING -> setOf(
                BankingTransferStatus.SUCCEEDED,
                BankingTransferStatus.FAILED,
                BankingTransferStatus.UNKNOWN,
                BankingTransferStatus.REVERSED,
            )
            BankingTransferStatus.UNKNOWN -> setOf(
                BankingTransferStatus.SUCCEEDED,
                BankingTransferStatus.FAILED,
                BankingTransferStatus.REVERSED,
            )
            BankingTransferStatus.SUCCEEDED,
            BankingTransferStatus.FAILED,
            BankingTransferStatus.REVERSED,
            -> emptySet()
        }
    }

    private fun clearReconciliationOperationState() {
        nextReconciliationAt = null
        reconciliationRetryExhausted = false
        clearReconciliationLease()
    }

    fun clearReconciliationLease() {
        reconciliationLockedAt = null
        reconciliationLockedUntil = null
        reconciliationLockOwner = null
    }

    private fun calculateReconciliationBackoff(baseBackoffMillis: Long, maxBackoffMillis: Long): Long {
        val base = baseBackoffMillis.coerceAtLeast(1)
        val max = maxBackoffMillis.coerceAtLeast(base)
        var delay = base
        repeat((reconciliationAttemptCount - 1).coerceAtLeast(0)) {
            delay = (delay * 2).coerceAtMost(max)
        }
        return delay.coerceAtMost(max)
    }

    companion object {
        fun create(
            transferId: Long,
            principalId: PrincipalId,
            idempotencyKey: String,
            requestHash: TransferRequestHash,
            command: TransferCommand,
        ): BankingTransfer {
            return BankingTransfer(
                transferId = transferId,
                principalId = principalId.value,
                idempotencyKey = idempotencyKey,
                requestHash = requestHash.value,
                withdrawalAccountId = command.withdrawalAccountId,
                depositBankCode = command.depositBankCode,
                depositAccountNumber = command.depositAccountNumber,
                amount = command.amount.amount,
                currency = command.amount.currency.uppercase(),
            )
        }
    }
}
