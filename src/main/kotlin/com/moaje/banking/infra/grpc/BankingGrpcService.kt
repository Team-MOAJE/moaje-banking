package com.moaje.banking.infra.grpc

import com.moaje.banking.application.service.ExternalBankingSyncCommand
import com.moaje.banking.application.service.ExternalBankingSyncService
import com.moaje.banking.common.restclient.MockBankingApiClient
import com.moaje.banking.domain.common.Money
import com.moaje.banking.domain.kftc.KftcApiLogStatus
import com.moaje.banking.domain.transfer.TransferCommand
import com.moaje.grpc.banking.BankingServiceGrpc
import com.moaje.grpc.banking.ExecuteTransferRequest
import com.moaje.grpc.banking.ExecuteTransferResponse
import com.moaje.grpc.banking.SyncExternalAccountTransactionsRequest
import com.moaje.grpc.banking.SyncExternalAccountTransactionsResponse
import io.grpc.Status
import io.grpc.stub.StreamObserver
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

@Component
class BankingGrpcService(
    private val mockBankingApiClient: MockBankingApiClient,
    private val externalBankingSyncService: ExternalBankingSyncService,
) : BankingServiceGrpc.BankingServiceImplBase() {
    /**
     * gRPC로 들어온 송금 실행 요청을 Mock Banking API 호출로 변환해 처리합니다.
     */
    override fun executeTransfer(
        request: ExecuteTransferRequest,
        responseObserver: StreamObserver<ExecuteTransferResponse>,
    ) {
        runCatching {
            val result = mockBankingApiClient.transferRequest(request.toCommand())
            ExecuteTransferResponse.newBuilder()
                .setStatus(result.status.toGrpcStatus())
                .setExternalTransactionId(result.externalTransactionId.orEmpty())
                .setResponseCode(result.responseCode.orEmpty())
                .setFailureReason(result.failureReason.orEmpty())
                .build()
        }.onSuccess { response ->
            responseObserver.onNext(response)
            responseObserver.onCompleted()
        }.onFailure { exception ->
            responseObserver.onError(
                Status.INTERNAL
                    .withDescription(exception.message)
                    .withCause(exception)
                    .asRuntimeException(),
            )
        }
    }

    /**
     * Asset 서비스의 pull-to-refresh 요청을 받아 외부 금융망 거래내역 동기화를 수행합니다.
     */
    override fun syncExternalAccountTransactions(
        request: SyncExternalAccountTransactionsRequest,
        responseObserver: StreamObserver<SyncExternalAccountTransactionsResponse>,
    ) {
        runCatching {
            val result = externalBankingSyncService.syncAccount(request.toCommand())
            SyncExternalAccountTransactionsResponse.newBuilder()
                .setSuccess(true)
                .setSyncedCount(result.syncedCount)
                .setMessage("외부 금융망 거래내역 동기화가 완료되었습니다.")
                .build()
        }.onSuccess { response ->
            responseObserver.onNext(response)
            responseObserver.onCompleted()
        }.onFailure { exception ->
            responseObserver.onError(
                Status.INTERNAL
                    .withDescription(exception.message)
                    .withCause(exception)
                    .asRuntimeException(),
            )
        }
    }

    /**
     * gRPC 송금 실행 요청 메시지를 Banking 내부 송금 커맨드로 변환합니다.
     */
    private fun ExecuteTransferRequest.toCommand(): TransferCommand {
        return TransferCommand(
            idempotencyKey = transferId.toString(),
            ci = ci,
            userName = userName,
            phoneNumber = phoneNumber,
            requesterUserId = transferId.toString(),
            withdrawalAccountId = withdrawalAccountId,
            depositBankCode = depositBankCode,
            depositAccountNumber = depositAccountNumber,
            amount = Money(BigDecimal.valueOf(amount.amount), amount.currency),
        )
    }

    /**
     * 외부 거래내역 동기화 gRPC 요청 메시지를 Banking 애플리케이션 커맨드로 변환합니다.
     */
    private fun SyncExternalAccountTransactionsRequest.toCommand(): ExternalBankingSyncCommand {
        return ExternalBankingSyncCommand(
            userId = userId,
            assetAccountId = assetAccountId,
            accountToken = accountToken,
            externalAccountNumber = externalAccountNumber,
            ci = ci,
            userName = userName,
            phoneNumber = phoneNumber,
            cursor = cursor.takeIf { it.isNotBlank() }?.let(Instant::parse) ?: Instant.EPOCH,
        )
    }

    /**
     * 내부 KFTC 통신 로그 상태를 gRPC 응답 상태 코드로 변환합니다.
     */
    private fun KftcApiLogStatus.toGrpcStatus(): ExecuteTransferResponse.Status {
        return when (this) {
            KftcApiLogStatus.SUCCESS -> ExecuteTransferResponse.Status.SUCCESS
            KftcApiLogStatus.TIMEOUT -> ExecuteTransferResponse.Status.TIMEOUT
            KftcApiLogStatus.SENT,
            KftcApiLogStatus.FAILED,
            KftcApiLogStatus.REVERSED,
            -> ExecuteTransferResponse.Status.FAILED
        }
    }
}
