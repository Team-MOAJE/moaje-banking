package com.moaje.banking.infra.grpc

import io.grpc.BindableService
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.io.File

class BankingGrpcServerTlsProperties {
    var enabled: Boolean = true
    var certificateChainPath: String = ""
    var privateKeyPath: String = ""
    var trustCertificateCollectionPath: String = ""
}

@Component
@ConfigurationProperties(prefix = "moaje.banking.grpc.server")
class BankingGrpcServerProperties {
    var port: Int = 9091
    var tls: BankingGrpcServerTlsProperties = BankingGrpcServerTlsProperties()
}

@Component
class BankingGrpcServer(
    private val properties: BankingGrpcServerProperties,
    private val services: List<BindableService>,
) : SmartLifecycle {
    private var server: Server? = null
    private var running: Boolean = false

    /**
     * 운영 기본값에서는 clientAuth=REQUIRE인 mTLS 서버만 시작한다.
     * 인증서가 없을 때 plaintext로 후퇴하면 설정 실수가 곧 인증 우회가 되므로 경로를 검증하고 즉시 시작을 실패시킨다.
     */
    override fun start() {
        if (running) return

        val builder = NettyServerBuilder.forPort(properties.port)
        if (properties.tls.enabled) {
            val tls = properties.tls
            val certificateChain = tls.certificateChainPath.requireReadableFile("Banking server certificate chain")
            val privateKey = tls.privateKeyPath.requireReadableFile("Banking server private key")
            val trustCertificates = tls.trustCertificateCollectionPath.requireReadableFile("Asset client trust certificates")
            builder.sslContext(
                GrpcSslContexts.forServer(certificateChain, privateKey)
                    .trustManager(trustCertificates)
                    .clientAuth(ClientAuth.REQUIRE)
                    .build(),
            )
        }
        services.forEach(builder::addService)
        server = builder.build().start()
        running = true
    }

    override fun stop() {
        server?.shutdown()
        running = false
    }

    override fun isRunning(): Boolean = running

    internal fun boundPort(): Int = server?.port ?: error("Banking gRPC server가 시작되지 않았습니다.")

    private fun String.requireReadableFile(description: String): File {
        require(isNotBlank()) { "$description 경로는 필수입니다." }
        return File(this).also { file ->
            require(file.isFile && file.canRead()) { "$description 파일을 읽을 수 없습니다. path=${file.path}" }
        }
    }
}
