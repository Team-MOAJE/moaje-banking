package com.moaje.banking.application.service.impl

import com.moaje.banking.application.event.BankingTransferEventPublisher
import com.moaje.banking.application.service.TransferCommandService
import com.moaje.banking.common.id.IdGenerator
import com.moaje.banking.common.restclient.MockBankingApiClient
import com.moaje.banking.common.restclient.MockBankingTransferResult
import com.moaje.banking.domain.account.BankingAccountNotFoundException
import com.moaje.banking.domain.transfer.BankingTransfer
import com.moaje.banking.domain.transfer.BankingTransferStatus
import com.moaje.banking.domain.kftc.KftcApiLog
import com.moaje.banking.domain.kftc.KftcApiLogStatus
import com.moaje.banking.domain.transfer.OperationType
import com.moaje.banking.domain.transfer.TransferCommand
import com.moaje.banking.domain.transfer.TransferRequestResult
import com.moaje.banking.domain.transfer.TransferRequestHash
import com.moaje.banking.domain.transfer.TransferStatus
import com.moaje.banking.repository.kftc.KftcApiLogRepository
import com.moaje.banking.repository.account.BankingAccountRepository
import com.moaje.banking.repository.transfer.BankingTransferRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.time.ZoneOffset

@Service
class TransferCommandServiceImpl(
    private val mockBankingApiClient: MockBankingApiClient,
    private val kftcApiLogRepository: KftcApiLogRepository,
    private val bankingAccountRepository: BankingAccountRepository,
    private val bankingTransferRepository: BankingTransferRepository,
    private val bankingTransferEventPublisher: BankingTransferEventPublisher,
    private val idGenerator: IdGenerator,
    private val transactionTemplate: TransactionTemplate,
) : TransferCommandService {
    /**
     * 사용자 송금 요청을 Banking 책임 안에서 실제 금융망 처리까지 수행합니다.
     *
     * Asset은 최종 원천이 아니므로 출금 가능 여부를 승인하지 않습니다.
     * Banking이 목업뱅킹 결과를 확정한 뒤 Asset에는 projection 갱신 이벤트만 발행합니다.
     */
    override fun request(command: TransferCommand): TransferRequestResult {
        val requestHash = TransferRequestHash.from(command)
        val decision = getOrCreateTransfer(command, requestHash)

        if (decision.existing) {
            return decision.transfer.toResult()
        }

        markProcessing(decision.transfer.transferId)
        val bankingResult = mockBankingApiClient.transferRequest(
            transferId = decision.transfer.transferId,
            command = command,
            withdrawalProviderAccountId = checkNotNull(decision.withdrawalProviderAccountId),
        )
        val savedTransfer = saveResult(decision.transfer.transferId, bankingResult)

        return TransferRequestResult(
            transferId = savedTransfer.transferId,
            publicTransferId = savedTransfer.publicTransferId(),
            status = savedTransfer.status.toTransferStatus(),
            message = savedTransfer.toMessage(),
            externalTransactionId = savedTransfer.externalTransactionId,
        )
    }

    private fun getOrCreateTransfer(
        command: TransferCommand,
        requestHash: TransferRequestHash,
    ): TransferDecision {
        return try {
            transactionTemplate.execute {
                val existing = bankingTransferRepository.findByPrincipalIdAndOperationTypeAndIdempotencyKey(
                    principalId = command.principalId.value,
                    operationType = OperationType.BANKING_TRANSFER,
                    idempotencyKey = command.idempotencyKey,
                )

                if (existing != null) {
                    existing.requireSameRequest(requestHash)
                    return@execute TransferDecision(existing, existing = true) // 기존 거래내역 존재 시 바로 반환
                }

                /*
                 * 외부 호출 전에 Banking이 관리하는 내부 계좌로 소유권과 연결 상태를 검증한다.
                 * 여기서 얻은 providerAccountId만 Adapter로 넘겨 실제 계좌번호가 Banking으로 역류하지 않게 한다.
                 */
                val account = bankingAccountRepository.findById(command.withdrawalAccountId).orElseThrow {
                    BankingAccountNotFoundException(command.withdrawalAccountId)
                }
                val withdrawalProviderAccountId = account.providerAccountIdForTransfer(command.principalId.value)

                val transfer = BankingTransfer.create(
                    transferId = idGenerator.nextId(), // TSID방식으로 채번
                    principalId = command.principalId,
                    idempotencyKey = command.idempotencyKey,
                    requestHash = requestHash,
                    command = command,
                )
                TransferDecision(
                    transfer = bankingTransferRepository.saveAndFlush(transfer),
                    existing = false,
                    withdrawalProviderAccountId = withdrawalProviderAccountId,
                )
            } ?: throw IllegalStateException("송금 거래 생성 트랜잭션 결과가 없습니다.")
        } catch (exception: DataIntegrityViolationException) {
            transactionTemplate.execute {
                val existing = bankingTransferRepository.findByPrincipalIdAndOperationTypeAndIdempotencyKey(
                    principalId = command.principalId.value,
                    operationType = OperationType.BANKING_TRANSFER,
                    idempotencyKey = command.idempotencyKey,
                ) ?: throw exception
                existing.requireSameRequest(requestHash)
                TransferDecision(existing, existing = true)
            } ?: throw IllegalStateException("송금 거래 조회 트랜잭션 결과가 없습니다.")
        }
    }

    private fun markProcessing(transferId: Long) {
        transactionTemplate.executeWithoutResult {
            val transfer = bankingTransferRepository.findByIdForUpdate(transferId)
                ?: throw IllegalStateException("송금 거래를 찾을 수 없습니다. transferId=$transferId")
            transfer.markProcessing()
        }
    }

    private fun saveResult(
        transferId: Long,
        bankingResult: MockBankingTransferResult,
    ): BankingTransfer {

        return transactionTemplate.execute {
            val transfer = bankingTransferRepository.findByIdForUpdate(transferId)
                ?: throw IllegalStateException("송금 거래를 찾을 수 없습니다. transferId=$transferId")

            when (bankingResult.status) {
                KftcApiLogStatus.SUCCESS -> transfer.markSucceeded(bankingResult.externalTransactionId)
                KftcApiLogStatus.FAILED -> transfer.markFailed(bankingResult.responseCode, bankingResult.failureReason)
                KftcApiLogStatus.TIMEOUT,
                KftcApiLogStatus.SENT,
                    -> transfer.markUnknown(bankingResult.responseCode, bankingResult.failureReason)

                KftcApiLogStatus.REVERSED -> transfer.markReversed(
                    originalExternalTransactionId = bankingResult.externalTransactionId,
                    externalReversalTransactionId = bankingResult.externalReversalTransactionId,
                    reason = bankingResult.reversalReason,
                    reversedAt = bankingResult.reversedAt
                        ?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) }
                        ?: LocalDateTime.now(),
                )
            }

            kftcApiLogRepository.save(
                KftcApiLog(
                    transferId = transferId,
                    externalRefNo = bankingResult.externalReversalTransactionId ?: bankingResult.externalTransactionId,
                    requestPayload = bankingResult.requestPayload,
                    responseCode = bankingResult.responseCode,
                    status = bankingResult.status,
                ),
            )

            if (bankingResult.status.isPublishToAsset()) {
                bankingTransferEventPublisher.publish(
                    // outbox 테이블로 Insert
                    bankingResult = bankingResult,
                    transferId = transferId,
                    assetTransactionId = transferId,
                    command = transfer.toEventCommand(),
                )
            }

            transfer
        } ?: throw IllegalStateException("송금 결과 저장 트랜잭션 결과가 없습니다.")
    }

    private fun BankingTransfer.toResult(): TransferRequestResult {
        return TransferRequestResult(
            transferId = transferId,
            publicTransferId = publicTransferId(),
            status = status.toTransferStatus(),
            message = toMessage(),
            externalTransactionId = externalTransactionId,
        )
    }

    private fun BankingTransfer.toEventCommand(): TransferCommand {
        return TransferCommand(
            principalId = com.moaje.banking.domain.transfer.PrincipalId(principalId),
            idempotencyKey = idempotencyKey,
            withdrawalAccountId = withdrawalAccountId,
            depositBankCode = depositBankCode,
            depositAccountNumber = depositAccountNumber,
            amount = com.moaje.banking.domain.common.Money(amount, currency),
        )
    }

    private fun BankingTransfer.publicTransferId(): String = "MOAJE-BNK-$transferId"

    private fun BankingTransferStatus.toTransferStatus(): TransferStatus {
        return when (this) {
            BankingTransferStatus.SUCCEEDED -> TransferStatus.COMPLETED
            BankingTransferStatus.FAILED -> TransferStatus.FAILED
            BankingTransferStatus.REVERSED -> TransferStatus.REVERSED
            BankingTransferStatus.REQUESTED,
            BankingTransferStatus.PROCESSING,
            BankingTransferStatus.UNKNOWN,
                -> TransferStatus.PROCESSING
        }
    }

    private fun BankingTransfer.toMessage(): String {
        return when (status) {
            BankingTransferStatus.SUCCEEDED -> "송금이 완료되었습니다."
            BankingTransferStatus.FAILED -> failureReason ?: "송금에 실패했습니다."
            BankingTransferStatus.REVERSED -> "송금이 외부 계정계에서 보상 처리되었습니다."
            BankingTransferStatus.UNKNOWN -> "송금 결과가 불명확하여 정산 확인이 필요합니다."
            BankingTransferStatus.REQUESTED,
            BankingTransferStatus.PROCESSING -> "송금 요청이 처리 중입니다."
        }
    }

    private data class TransferDecision(
        val transfer: BankingTransfer,
        val existing: Boolean,
        val withdrawalProviderAccountId: String? = null,
    )

    /**
     * 송금API 통신결과 상태값에 따라 outbox Table에 데이터 저장여부를 결정한다.
     */
    private fun KftcApiLogStatus.isPublishToAsset(): Boolean =
        when (this) {
            KftcApiLogStatus.SUCCESS, KftcApiLogStatus.FAILED, KftcApiLogStatus.REVERSED
                -> true

            KftcApiLogStatus.TIMEOUT, KftcApiLogStatus.SENT
                -> false
        }
}
