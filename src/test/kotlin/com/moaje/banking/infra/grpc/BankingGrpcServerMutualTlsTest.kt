package com.moaje.banking.infra.grpc

import com.moaje.grpc.banking.BankingServiceGrpc
import com.moaje.grpc.banking.GetTransferProjectionStatesRequest
import com.moaje.grpc.banking.GetTransferProjectionStatesResponse
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.netty.handler.ssl.util.SelfSignedCertificate
import io.grpc.stub.StreamObserver
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

@DisplayName("Banking gRPC 상호 TLS")
class BankingGrpcServerMutualTlsTest {
    @Test
    @DisplayName("mTLS가 활성화됐는데 인증서 경로가 없으면 plaintext로 후퇴하지 않고 시작에 실패한다")
    fun failsClosedWhenTlsFilesAreMissing() {
        // given: 운영 기본값인 TLS enabled 상태에서 인증서 설정을 하나도 제공하지 않는다.
        val server = BankingGrpcServer(BankingGrpcServerProperties().apply { port = 0 }, emptyList())

        // when & then: 설정 오류를 조기에 드러내 비인증 gRPC 서버가 열리는 일을 막는다.
        assertThatThrownBy { server.start() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("certificate chain")
    }

    @Test
    @DisplayName("신뢰한 Asset client certificate가 있을 때만 RPC handshake를 허용한다")
    fun requiresTrustedAssetClientCertificate() {
        // given: 테스트 실행 중에만 존재하는 Banking 서버 인증서와 Asset 클라이언트 인증서를 만든다.
        val bankingCertificate = SelfSignedCertificate("banking.test")
        val assetCertificate = SelfSignedCertificate("asset.test")
        val properties = BankingGrpcServerProperties().apply {
            port = 0
            tls.certificateChainPath = bankingCertificate.certificate().absolutePath
            tls.privateKeyPath = bankingCertificate.privateKey().absolutePath
            tls.trustCertificateCollectionPath = assetCertificate.certificate().absolutePath
        }
        val service = object : BankingServiceGrpc.BankingServiceImplBase() {
            override fun getTransferProjectionStates(
                request: GetTransferProjectionStatesRequest,
                responseObserver: StreamObserver<GetTransferProjectionStatesResponse>,
            ) {
                responseObserver.onNext(GetTransferProjectionStatesResponse.getDefaultInstance())
                responseObserver.onCompleted()
            }
        }
        val server = BankingGrpcServer(properties, listOf(service))

        try {
            server.start()
            val trustedChannel = NettyChannelBuilder.forAddress("localhost", server.boundPort())
                .overrideAuthority("banking.test")
                .sslContext(
                    GrpcSslContexts.forClient()
                        .trustManager(bankingCertificate.certificate())
                        .keyManager(assetCertificate.certificate(), assetCertificate.privateKey())
                        .build(),
                )
                .build()
            val noClientCertificateChannel = NettyChannelBuilder.forAddress("localhost", server.boundPort())
                .overrideAuthority("banking.test")
                .sslContext(GrpcSslContexts.forClient().trustManager(bankingCertificate.certificate()).build())
                .build()

            try {
                // when & then: Asset 인증서를 제시한 채널은 정상 응답을 받는다.
                val response = BankingServiceGrpc.newBlockingStub(trustedChannel)
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .getTransferProjectionStates(GetTransferProjectionStatesRequest.getDefaultInstance())
                assertThat(response).isNotNull

                // client certificate가 없으면 TLS handshake 단계에서 거부되어 Application RPC에 도달하지 않는다.
                assertThatThrownBy {
                    BankingServiceGrpc.newBlockingStub(noClientCertificateChannel)
                        .withDeadlineAfter(3, TimeUnit.SECONDS)
                        .getTransferProjectionStates(GetTransferProjectionStatesRequest.getDefaultInstance())
                }.isInstanceOf(StatusRuntimeException::class.java)
            } finally {
                trustedChannel.shutdownNow()
                noClientCertificateChannel.shutdownNow()
            }
        } finally {
            server.stop()
            bankingCertificate.delete()
            assetCertificate.delete()
        }
    }
}
