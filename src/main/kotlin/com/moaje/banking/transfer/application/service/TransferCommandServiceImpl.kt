package com.moaje.banking.transfer.application.service

import com.moaje.banking.transfer.messaging.publisher.BankingTransferEventPublisher
import com.moaje.banking.common.id.IdGenerator
import com.moaje.banking.integration.mockbanking.MockBankingApiClient
import com.moaje.banking.integration.mockbanking.model.MockBankingTransferResult
import com.moaje.banking.transfer.domain.BankingTransfer
import com.moaje.banking.transfer.domain.BankingTransferStatus
import com.moaje.banking.kftc.domain.KftcApiLog
import com.moaje.banking.kftc.domain.KftcApiLogStatus
import com.moaje.banking.transfer.domain.OperationType
import com.moaje.banking.transfer.domain.TransferCommand
import com.moaje.banking.transfer.application.model.TransferRequestResult
import com.moaje.banking.transfer.domain.TransferRequestHash
import com.moaje.banking.transfer.application.model.TransferStatus
import com.moaje.banking.kftc.persistence.KftcApiLogRepository
import com.moaje.banking.account.persistence.BankingAccountRepository
import com.moaje.banking.transfer.persistence.BankingTransferRepository
import com.moaje.banking.transfer.support.BankingAccountNotFoundException
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
        val requestHash = TransferRequestHash.from(command) // request 값 hashing
        val decision = getOrCreateTransfer(command, requestHash) // 새로운 송금 트랜잭션 생성(이미 존재한다면 가져오기)

        if (decision.existing) { // 이미 존재하는 트랜잭션이라면 return
            return decision.transfer.toResult()
        }

        markProcessing(decision.transfer.transferId) // 새로운 송금 트랜잭션에 대해 processing 이라는 status를 남기는 짧은 TX
        val bankingResult = mockBankingApiClient.transferRequest( // MockBanking 서버로 API 통신 시작
            transferId = decision.transfer.transferId,
            command = command,
            withdrawalProviderAccountId = checkNotNull(decision.withdrawalProviderAccountId),
        )
        val savedTransfer = saveResult(decision.transfer.transferId, bankingResult) // 완료된 통신결과를 가공

        return TransferRequestResult( // 송금 트랜잭션 완료 객체 반환
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
                    existing.requireSameRequest(requestHash) // 해싱을 통해 같은 거래인지 확인
                    return@execute TransferDecision(existing, existing = true) // 기존 거래내역 존재 시 바로 반환후 TX 종료
                }


                 //외부 호출 전에 Banking이 관리하는 내부 계좌로 소유권과 연결 상태를 검증한다.
                val account = bankingAccountRepository.findById(command.withdrawalAccountId).orElseThrow {
                    BankingAccountNotFoundException(command.withdrawalAccountId)
                }

                // 출금계좌번호 별칭(mockup에서 제공해준 계좌번호 연동Id)
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
        } catch (exception: DataIntegrityViolationException) { // 중복되는 트랜잭션데이터(BankingTransfer Entity)가 생겨서 정합성위배에 걸리는 경우.
            transactionTemplate.execute {
                val existing = bankingTransferRepository.findByPrincipalIdAndOperationTypeAndIdempotencyKey(
                    principalId = command.principalId.value,
                    operationType = OperationType.BANKING_TRANSFER,
                    idempotencyKey = command.idempotencyKey,
                ) ?: throw exception
                existing.requireSameRequest(requestHash) // 충돌예외가 나지 않으면 hash값이 같음. (이미 DB에 존재)
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

        return transactionTemplate.execute { // 짧은 트랜잭션에서 송금거래내역 저장 및 outbox 발급
            val transfer = bankingTransferRepository.findByIdForUpdate(transferId)
                ?: throw IllegalStateException("송금 거래를 찾을 수 없습니다. transferId=$transferId")

            // 응답을 읽지 못한 경우와 미확정 HTTP 오류도 실제 송금 여부는 알 수 없다.
            // UNKNOWN으로 남겨 대사 대상에서 빠지지 않게 하고 확정 이벤트는 만들지 않는다.
            when (bankingResult.status) { // 가져온 거래결과의 상태에 따라 다르게 구분하여 DB UPDATE
                KftcApiLogStatus.SUCCESS -> transfer.markSucceeded(bankingResult.externalTransactionId)
                KftcApiLogStatus.FAILED -> transfer.markFailed(bankingResult.responseCode, bankingResult.failureReason)
                KftcApiLogStatus.TIMEOUT,
                KftcApiLogStatus.UNREADABLE_RESPONSE,
                KftcApiLogStatus.UNCONFIRMED_HTTP_ERROR,
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

    // 처리객체 결과로 가공해서 내보낸다.
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
            principalId = com.moaje.banking.transfer.domain.PrincipalId(principalId),
            idempotencyKey = idempotencyKey,
            withdrawalAccountId = withdrawalAccountId,
            depositBankCode = depositBankCode,
            depositAccountNumber = depositAccountNumber,
            amount = com.moaje.banking.common.domain.Money(amount, currency),
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

            KftcApiLogStatus.TIMEOUT, KftcApiLogStatus.SENT,
            KftcApiLogStatus.UNREADABLE_RESPONSE, KftcApiLogStatus.UNCONFIRMED_HTTP_ERROR
                -> false
        }
}
