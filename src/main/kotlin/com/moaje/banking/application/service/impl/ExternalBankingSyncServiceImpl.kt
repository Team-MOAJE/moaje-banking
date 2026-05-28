package com.moaje.banking.application.service.impl

import com.moaje.banking.application.asset.AssetTransferClient
import com.moaje.banking.application.asset.ExternalAssetTransaction
import com.moaje.banking.application.service.ExternalBankingSyncAccountProperties
import com.moaje.banking.application.service.ExternalBankingSyncCommand
import com.moaje.banking.application.service.ExternalBankingSyncProperties
import com.moaje.banking.application.service.ExternalBankingSyncResult
import com.moaje.banking.application.service.ExternalBankingSyncService
import com.moaje.banking.common.bank.TransferHistoryResponse
import com.moaje.banking.common.restclient.MockBankingApiClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant

@Service
class ExternalBankingSyncServiceImpl(
    private val properties: ExternalBankingSyncProperties,
    private val mockBankingApiClient: MockBankingApiClient,
    private val assetTransferClient: AssetTransferClient,
) : ExternalBankingSyncService {
    /**
     * 설정된 계좌들의 외부 금융망 거래내역을 cursor 이후 기준으로 조회해 Asset 원장에 반영합니다.
     */
    override fun syncAfter(cursor: Instant) {
        properties.accounts
            .filter { it.isConfigured() }
            .forEach { account -> syncAccount(account.toCommand(cursor)) }
    }

    /**
     * 단일 계좌의 외부 거래내역을 조회하고 Asset 원장 반영 요청까지 수행합니다.
     */
    override fun syncAccount(command: ExternalBankingSyncCommand): ExternalBankingSyncResult {
        val syncedCount = mockBankingApiClient.fetchAccountHistories(
            accountNumber = command.externalAccountNumber,
            ci = command.ci,
            userName = command.userName,
            phoneNumber = command.phoneNumber,
        )
            .asSequence()
            .filter { it.createdAt.isAfter(command.cursor) }
            .sortedBy { it.createdAt }
            .map { it.toExternalAssetTransaction(command) }
            .onEach(assetTransferClient::applyExternalTransaction)
            .count()

        return ExternalBankingSyncResult(syncedCount = syncedCount)
    }

    /**
     * 스케줄러가 활성화되어 있으면 주기적으로 외부 금융망 동기화를 수행합니다.
     */
    @Scheduled(fixedDelayString = "\${moaje.banking.external-sync.fixed-delay-ms:60000}")
    fun syncBySchedule() {
        if (!properties.enabled) {
            return
        }

        syncAfter(properties.cursor)
    }

    /**
     * 단일 계좌의 외부 거래내역을 조회하고, cursor 이후 내역만 Asset에 멱등 반영합니다.
     */
    private fun TransferHistoryResponse.toExternalAssetTransaction(command: ExternalBankingSyncCommand): ExternalAssetTransaction {
        return ExternalAssetTransaction(
            userId = command.userId,
            accountId = command.assetAccountId,
            accountToken = command.accountToken,
            externalTransactionId = transactionId,
            type = type,
            amount = BigDecimal.valueOf(amount),
            targetToken = counterpartyAccountNumber,
            occurredAt = createdAt,
        )
    }

    /**
     * 동기화 대상 계좌 설정이 실제 호출 가능한 값으로 채워져 있는지 확인합니다.
     */
    private fun ExternalBankingSyncAccountProperties.isConfigured(): Boolean {
        return userId > 0 &&
            assetAccountId > 0 &&
            accountToken.isNotBlank() &&
            externalAccountNumber.isNotBlank() &&
            ci.isNotBlank() &&
            userName.isNotBlank() &&
            phoneNumber.isNotBlank()
    }

    /**
     * 설정 기반 동기화 대상 계좌 정보를 실행 커맨드로 변환합니다.
     */
    private fun ExternalBankingSyncAccountProperties.toCommand(cursor: Instant): ExternalBankingSyncCommand {
        return ExternalBankingSyncCommand(
            userId = userId,
            assetAccountId = assetAccountId,
            accountToken = accountToken,
            externalAccountNumber = externalAccountNumber,
            ci = ci,
            userName = userName,
            phoneNumber = phoneNumber,
            cursor = cursor,
        )
    }
}
