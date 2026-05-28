package com.moaje.banking.application.service.impl

import com.moaje.banking.application.event.BankingTransferEventPublisher
import com.moaje.banking.application.service.TransferEventService
import com.moaje.banking.common.restclient.MockBankingApiClient
import com.moaje.banking.domain.kftc.KftcApiLog
import com.moaje.banking.domain.transfer.TransferCommand
import com.moaje.banking.domain.transfer.TransferRequestedEvent
import com.moaje.banking.repository.kftc.KftcApiLogRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TransferEventServiceImpl(
    private val mockBankingApiClient: MockBankingApiClient,
    private val kftcApiLogRepository: KftcApiLogRepository,
    private val bankingTransferEventPublisher: BankingTransferEventPublisher,
) : TransferEventService {
    /**
     * Asset 서비스가 발행한 송금 요청 이벤트를 받아 실제 Mock Banking API 송금을 수행합니다.
     */
    @Transactional
    override fun process(event: TransferRequestedEvent) {
        val bankingResult = mockBankingApiClient.transferRequest(event.toCommand())

        kftcApiLogRepository.save(
            KftcApiLog(
                transferId = event.transferId,
                externalRefNo = bankingResult.externalTransactionId,
                requestPayload = bankingResult.requestPayload,
                responseCode = bankingResult.responseCode,
                status = bankingResult.status,
            ),
        )

        bankingTransferEventPublisher.publish(
            bankingResult = bankingResult,
            transferId = event.transferId,
            assetTransactionId = event.assetTransactionId,
        )
    }

    /**
     * Kafka 이벤트에 담긴 송금 정보를 Mock Banking API 호출에 필요한 커맨드로 변환합니다.
     */
    private fun TransferRequestedEvent.toCommand(): TransferCommand {
        return TransferCommand(
            idempotencyKey = eventId,
            ci = ci,
            userName = userName,
            phoneNumber = phoneNumber,
            requesterUserId = requesterUserId,
            withdrawalAccountId = withdrawalAccountId,
            depositBankCode = depositBankCode,
            depositAccountNumber = depositAccountNumber,
            amount = amount,
            requestedAt = occurredAt,
        )
    }
}
