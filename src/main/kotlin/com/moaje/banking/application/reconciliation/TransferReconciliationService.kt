package com.moaje.banking.application.reconciliation

import com.moaje.banking.application.event.BankingTransferEventPublisher
import com.moaje.banking.common.restclient.MockBankingApiClient
import com.moaje.banking.common.restclient.MockBankingTransferResult
import com.moaje.banking.domain.common.Money
import com.moaje.banking.domain.kftc.KftcApiLog
import com.moaje.banking.domain.kftc.KftcApiLogStatus
import com.moaje.banking.domain.transfer.BankingTransfer
import com.moaje.banking.domain.transfer.BankingTransferStatus
import com.moaje.banking.domain.transfer.PrincipalId
import com.moaje.banking.domain.transfer.TransferCommand
import com.moaje.banking.repository.kftc.KftcApiLogRepository
import com.moaje.banking.repository.transfer.BankingTransferRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.net.InetAddress
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

@Service
class TransferReconciliationService(
    private val bankingTransferRepository: BankingTransferRepository,
    private val kftcApiLogRepository: KftcApiLogRepository,
    private val mockBankingApiClient: MockBankingApiClient,
    private val bankingTransferEventPublisher: BankingTransferEventPublisher,
    private val properties: TransferReconciliationProperties,
    private val metrics: TransferReconciliationMetrics,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lockOwner = "${resolveHostName()}-${UUID.randomUUID()}"

    /**
     * 대사 배치는 후보 조회, lease 선점, 외부 조회, 결과 반영을 단계별로 나눈다.
     * Mock Banking 호출 동안 DB 커넥션을 잡지 않으면서도, lease owner 검증으로 다중 Worker 중복 처리를 줄인다.
     */
    fun reconcileBatch(): TransferReconciliationResult {
        val now = LocalDateTime.now()
        val staleBefore = now.minus(Duration.ofMillis(properties.processingStaleAfterMs.coerceAtLeast(1)))
        val transferIds = bankingTransferRepository.findTransferIdsForReconciliation(
            now = now,
            processingStartedBefore = staleBefore,
            pageable = PageRequest.of(0, properties.batchSize.coerceAtLeast(1)),
        )

        var resolved = 0
        var failed = 0
        var stillUnknown = 0

        transferIds.forEach { transferId ->
            if (!claimTransfer(transferId, now, staleBefore)) {
                return@forEach
            }
            metrics.recordClaimed()

            val lookupResult = try {
                mockBankingApiClient.lookupTransferResult(transferId)
            } catch (exception: Exception) {
                MockBankingTransferResult(
                    status = KftcApiLogStatus.TIMEOUT,
                    requestPayload = """{"clientTransferId":"$transferId"}""",
                    responseCode = "LOOKUP_EXCEPTION",
                    failureReason = exception.rootMessage(),
                )
            }

            when (lookupResult.status) {
                KftcApiLogStatus.SUCCESS,
                KftcApiLogStatus.FAILED,
                KftcApiLogStatus.REVERSED,
                -> {
                    if (applyConfirmedResult(transferId, lookupResult)) {
                        resolved += 1
                        metrics.recordResolved()
                        if (lookupResult.status == KftcApiLogStatus.REVERSED) {
                            metrics.recordReversed()
                        }
                    }
                }
                KftcApiLogStatus.TIMEOUT,
                KftcApiLogStatus.SENT,
                -> {
                    val retryExhausted = recordLookupFailure(transferId, lookupResult.responseCode, lookupResult.failureReason)
                    metrics.recordLookupFailed(retryExhausted)
                    stillUnknown += 1
                }
            }
        }

        return TransferReconciliationResult(
            scanned = transferIds.size,
            resolved = resolved,
            failed = failed,
            stillUnknown = stillUnknown,
        )
    }

    /**
     * 후보 목록은 오래된 스냅샷일 수 있으므로 선점 update를 최종 방어선으로 둔다.
     * lockOwner와 lockedUntil을 남겨 Worker가 죽어도 lease 만료 후 다른 인스턴스가 복구할 수 있다.
     */
    private fun claimTransfer(
        transferId: Long,
        now: LocalDateTime,
        staleBefore: LocalDateTime,
    ): Boolean {
        val lockedUntil = now.plus(Duration.ofMillis(properties.leaseDurationMs.coerceAtLeast(1)))
        return transactionTemplate.execute {
            bankingTransferRepository.claimTransferForReconciliation(
                transferId = transferId,
                now = now,
                lockedUntil = lockedUntil,
                lockOwner = lockOwner,
                processingStartedBefore = staleBefore,
            ) == 1
        } ?: false
    }

    /**
     * 확정 결과와 Outbox 저장은 같은 DB 트랜잭션 안에서 수행한다.
     * REVERSED는 여기서 보상을 실행했다는 뜻이 아니라 Mock Banking에서 이미 끝난 반전을 발견했다는 뜻이다.
     * 원 성공 이벤트도 유실됐을 수 있으므로 REVERSED 발견 시 Completed와 Reversed Outbox를 함께 저장한다.
     * 처리 전 lease owner를 다시 확인해 다른 Worker가 선점한 뒤 도착한 늦은 조회 응답은 반영하지 않는다.
     */
    private fun applyConfirmedResult(
        transferId: Long,
        bankingResult: MockBankingTransferResult,
    ): Boolean {
        return transactionTemplate.execute {
            val transfer = bankingTransferRepository.findByIdForUpdate(transferId)
                ?: return@execute false
            if (!transfer.isReconciliationLockedBy(lockOwner)) {
                log.info("Skip reconciliation result because lease owner changed. transferId={}", transferId)
                return@execute false
            }
            if (transfer.status != BankingTransferStatus.UNKNOWN && transfer.status != BankingTransferStatus.PROCESSING) {
                return@execute false
            }

            when (bankingResult.status) {
                KftcApiLogStatus.SUCCESS -> transfer.markSucceeded(bankingResult.externalTransactionId)
                KftcApiLogStatus.FAILED -> transfer.markFailed(bankingResult.responseCode, bankingResult.failureReason)
                KftcApiLogStatus.REVERSED -> transfer.markReversed(
                    originalExternalTransactionId = bankingResult.externalTransactionId,
                    externalReversalTransactionId = bankingResult.externalReversalTransactionId,
                    reason = bankingResult.reversalReason,
                    reversedAt = bankingResult.reversedAt
                        ?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) }
                        ?: LocalDateTime.now(),
                )
                else -> return@execute false
            }
            transfer.recordReconciliationResolved()
            saveKftcLog(transferId, bankingResult)
            if (bankingResult.status == KftcApiLogStatus.REVERSED) {
                bankingTransferEventPublisher.publishRecoveredReversed(
                    bankingResult = bankingResult,
                    transferId = transferId,
                    assetTransactionId = transferId,
                    command = transfer.toEventCommand(),
                )
            } else {
                bankingTransferEventPublisher.publish(
                    bankingResult = bankingResult,
                    transferId = transferId,
                    assetTransactionId = transferId,
                    command = transfer.toEventCommand(),
                )
            }
            true
        } ?: false
    }

    private fun recordLookupFailure(
        transferId: Long,
        code: String?,
        reason: String?,
    ): Boolean {
        return transactionTemplate.execute {
            val transfer = bankingTransferRepository.findByIdForUpdate(transferId)
                ?: return@execute false
            if (!transfer.isReconciliationLockedBy(lockOwner)) {
                log.info("Skip reconciliation failure because lease owner changed. transferId={}", transferId)
                return@execute false
            }
            if (transfer.status != BankingTransferStatus.UNKNOWN && transfer.status != BankingTransferStatus.PROCESSING) {
                return@execute false
            }
            val wasRetryExhausted = transfer.reconciliationRetryExhausted
            transfer.recordReconciliationLookupFailure(
                code = code,
                reason = reason,
                maxAttempts = properties.maxAttempts,
                baseBackoffMillis = properties.baseBackoffMs,
                maxBackoffMillis = properties.maxBackoffMs,
            )
            !wasRetryExhausted && transfer.reconciliationRetryExhausted
        } ?: false
    }

    private fun saveKftcLog(
        transferId: Long,
        bankingResult: MockBankingTransferResult,
    ) {
        kftcApiLogRepository.save(
            KftcApiLog(
                transferId = transferId,
                externalRefNo = bankingResult.externalReversalTransactionId ?: bankingResult.externalTransactionId,
                requestPayload = bankingResult.requestPayload,
                responseCode = bankingResult.responseCode,
                status = bankingResult.status,
            ),
        )
    }

    private fun BankingTransfer.isReconciliationCandidate(staleBefore: LocalDateTime): Boolean {
        return (status == BankingTransferStatus.UNKNOWN && reconciliationRequired) ||
            (status == BankingTransferStatus.PROCESSING &&
                processingStartedAt != null &&
                processingStartedAt!! <= staleBefore)
    }

    private fun BankingTransfer.toEventCommand(): TransferCommand {
        return TransferCommand(
            principalId = PrincipalId(principalId),
            idempotencyKey = idempotencyKey,
            withdrawalAccountId = withdrawalAccountId,
            depositBankCode = depositBankCode,
            depositAccountNumber = depositAccountNumber,
            amount = Money(amount, currency),
        )
    }

    private fun Exception.rootMessage(): String {
        val root = generateSequence(this as Throwable) { it.cause }.last()
        return root.message ?: root::class.java.simpleName
    }

    private fun resolveHostName(): String {
        return runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown-host")
    }
}
