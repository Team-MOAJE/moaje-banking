package com.moaje.banking.application.asset

import com.moaje.banking.domain.transfer.TransferCommand
import com.moaje.common.Money as ProtoMoney
import com.moaje.common.Idempotency
import com.moaje.grpc.asset.ApplyExternalTransactionRequest
import com.moaje.grpc.asset.AssetServiceGrpc
import com.moaje.grpc.asset.ReserveTransferRequest
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "moaje.banking.asset")
class AssetGrpcClientProperties {
    var grpcHost: String = "localhost"
    var grpcPort: Int = 9090
}

@Component
class AssetTransferClient(
    private val properties: AssetGrpcClientProperties,
) : DisposableBean {
    private val channel: ManagedChannel by lazy {
        ManagedChannelBuilder
            .forAddress(properties.grpcHost, properties.grpcPort)
            .usePlaintext()
            .build()
    }

    private val assetServiceBlockingClient: AssetServiceGrpc.AssetServiceBlockingStub by lazy {
        AssetServiceGrpc.newBlockingStub(channel)
    }

    /**
     * Asset 서비스의 gRPC ReserveTransfer API를 호출하여 계좌 잔액 가승인을 요청합니다.
     */
    fun reserve(command: TransferCommand): AssetTransferReservation {
        val response = assetServiceBlockingClient.reserveTransfer(command.toReserveTransferRequest())

        return AssetTransferReservation(
            transferId = response.transferId,
            publicTransferId = response.publicTransferId,
            assetTransactionId = response.assetTransactionId,
        )
    }

    /**
     * 외부 금융망에서 발견한 신규 거래 내역을 Asset 원장에 반영하도록 gRPC로 요청합니다.
     */
    fun applyExternalTransaction(transaction: ExternalAssetTransaction) {
        val response = assetServiceBlockingClient.applyExternalTransaction(transaction.toApplyExternalTransactionRequest())
        require(response.success) { response.message }
    }

    /**
     * 애플리케이션 종료 시 gRPC 채널을 닫아 네트워크 리소스를 정리합니다.
     */
    override fun destroy() {
        channel.shutdown()
    }

    /**
     * Banking 도메인의 송금 커맨드를 Asset gRPC 계약 메시지로 변환합니다.
     */
    private fun TransferCommand.toReserveTransferRequest(): ReserveTransferRequest {
        return ReserveTransferRequest.newBuilder()
            .setUserId(requesterUserId.toLong())
            .setAccountId(withdrawalAccountId.toLong())
            .setTargetToken(depositAccountNumber)
            .setAmount(
                ProtoMoney.newBuilder()
                    .setAmount(amount.amount.toLong())
                    .setCurrency(amount.currency)
                    .build(),
            )
            .setIdempotency(Idempotency.newBuilder().setKey(idempotencyKey).build())
            .setCi(ci)
            .setUserName(userName)
            .setPhoneNumber(phoneNumber)
            .setWithdrawalAccountId(withdrawalAccountId)
            .setDepositBankCode(depositBankCode)
            .setDepositAccountNumber(depositAccountNumber)
            .build()
    }

    /**
     * 외부 거래 내역 DTO를 Asset gRPC 계약 메시지로 변환합니다.
     */
    private fun ExternalAssetTransaction.toApplyExternalTransactionRequest(): ApplyExternalTransactionRequest {
        return ApplyExternalTransactionRequest.newBuilder()
            .setUserId(userId)
            .setAccountId(accountId)
            .setAccountToken(accountToken)
            .setExternalTransactionId(externalTransactionId)
            .setType(type)
            .setAmount(
                ProtoMoney.newBuilder()
                    .setAmount(amount.toLong())
                    .setCurrency("KRW")
                    .build(),
            )
            .setTargetToken(targetToken.orEmpty())
            .setOccurredAt(occurredAt.toString())
            .build()
    }
}
