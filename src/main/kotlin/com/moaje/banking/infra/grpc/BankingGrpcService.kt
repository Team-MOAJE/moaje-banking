package com.moaje.banking.infra.grpc

import com.moaje.banking.application.service.AccountProjectionSnapshotQueryService
import com.moaje.banking.application.service.TransferProjectionQueryService
import com.moaje.banking.application.service.TransferProjectionStateResult
import com.moaje.common.Money as ProtoMoney
import com.moaje.grpc.banking.BankingServiceGrpc
import com.moaje.grpc.banking.ExternalProjectionTransaction
import com.moaje.grpc.banking.GetAccountProjectionSnapshotRequest
import com.moaje.grpc.banking.GetAccountProjectionSnapshotResponse
import com.moaje.grpc.banking.GetTransferProjectionStatesRequest
import com.moaje.grpc.banking.GetTransferProjectionStatesResponse
import com.moaje.grpc.banking.TransferProjectionState
import io.grpc.Status
import io.grpc.stub.StreamObserver
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class BankingGrpcService(
    private val transferProjectionQueryService: TransferProjectionQueryService,
    private val accountProjectionSnapshotQueryService: AccountProjectionSnapshotQueryService,
) : BankingServiceGrpc.BankingServiceImplBase() {
    /**
     * Asset 자동 대사는 평문 계좌정보를 전달하지 않고 내부 accountId와 transferId만으로 Journal을 조회한다.
     * 이 RPC는 외부 송금을 실행하지 않는 읽기 전용 Adapter이며, Projection 복구 판단은 Asset Application 계층이 담당한다.
     */
    override fun getTransferProjectionStates(
        request: GetTransferProjectionStatesRequest,
        responseObserver: StreamObserver<GetTransferProjectionStatesResponse>,
    ) {
        runCatching {
            val states = transferProjectionQueryService.getStates(request.accountId, request.transferIdsList)
            GetTransferProjectionStatesResponse.newBuilder()
                .addAllTransfers(states.map { it.toGrpcResponse() })
                .build()
        }.respond(responseObserver)
    }

    /**
     * Asset에는 providerAccountId나 평문 계좌번호를 공개하지 않고 내부 accountId만 받는다.
     * Banking이 계정계 식별자 매핑을 소유하므로 Adapter 교체 시에도 Asset 계약은 바뀌지 않는다.
     */
    override fun getAccountProjectionSnapshot(
        request: GetAccountProjectionSnapshotRequest,
        responseObserver: StreamObserver<GetAccountProjectionSnapshotResponse>,
    ) {
        runCatching {
            val snapshot = accountProjectionSnapshotQueryService.getSnapshot(
                request.accountId,
                Instant.ofEpochMilli(request.cursorEpochMillis.coerceAtLeast(0)),
            )
            GetAccountProjectionSnapshotResponse.newBuilder()
                .setAccountId(snapshot.accountId)
                .setPrincipalId(snapshot.principalId)
                .setBalance(
                    ProtoMoney.newBuilder()
                        .setAmount(snapshot.balance.toLong())
                        .setCurrency(snapshot.currency)
                        .build(),
                )
                .setAccountStatus(snapshot.accountStatus)
                .setAsOfEpochMillis(snapshot.asOf.toEpochMilli())
                .addAllTransactions(snapshot.transactions.map { transaction ->
                    ExternalProjectionTransaction.newBuilder()
                        .setExternalTransactionId(transaction.externalTransactionId)
                        .setType(transaction.type)
                        .setAmount(
                            ProtoMoney.newBuilder()
                                .setAmount(transaction.amount.toLong())
                                .setCurrency(transaction.currency)
                                .build(),
                        )
                        .setOccurredAtEpochMillis(transaction.occurredAt.toEpochMilli())
                        .apply { transaction.completedAt?.let { setCompletedAtEpochMillis(it.toEpochMilli()) } }
                        .build()
                })
                .build()
        }.respond(responseObserver)
    }

    private fun <T> Result<T>.respond(responseObserver: StreamObserver<T>) {
        onSuccess { response ->
            responseObserver.onNext(response)
            responseObserver.onCompleted()
        }.onFailure { exception ->
            val status = if (exception is IllegalArgumentException) Status.INVALID_ARGUMENT else Status.INTERNAL
            responseObserver.onError(status.withDescription(exception.message).withCause(exception).asRuntimeException())
        }
    }

    private fun TransferProjectionStateResult.toGrpcResponse(): TransferProjectionState {
        return TransferProjectionState.newBuilder()
            .setTransferId(transferId)
            .setPrincipalId(principalId)
            .setAccountId(accountId)
            .setAmount(
                ProtoMoney.newBuilder()
                    .setAmount(amount.toLong())
                    .setCurrency(currency)
                    .build(),
            )
            .setStatus(TransferProjectionState.Status.valueOf(status.name))
            .setExternalTransactionId(externalTransactionId.orEmpty())
            .setExternalReversalTransactionId(externalReversalTransactionId.orEmpty())
            .setFailureCode(failureCode.orEmpty())
            .setFailureReason(failureReason.orEmpty())
            .setReversalReason(reversalReason.orEmpty())
            .build()
    }
}
