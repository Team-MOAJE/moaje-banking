package com.moaje.banking.api.grpc

import com.moaje.grpc.banking.BankingServiceGrpc
import com.moaje.grpc.banking.GetOwnedAccountNumberRequest
import com.moaje.grpc.banking.GetOwnedAccountNumberResponse
import io.grpc.Attributes
import io.grpc.Grpc
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.Status
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import javax.net.ssl.SSLSession
import javax.security.auth.x500.X500Principal

class OwnedAccountNumberInterceptorTest {
    @Suppress("UNCHECKED_CAST")
    private val call = Mockito.mock(ServerCall::class.java) as ServerCall<GetOwnedAccountNumberRequest, GetOwnedAccountNumberResponse>
    @Suppress("UNCHECKED_CAST")
    private val next = Mockito.mock(ServerCallHandler::class.java) as ServerCallHandler<GetOwnedAccountNumberRequest, GetOwnedAccountNumberResponse>
    private val interceptor = OwnedAccountNumberInterceptor()

    private fun givenPeer(name: String?) {
        Mockito.`when`(call.methodDescriptor).thenReturn(BankingServiceGrpc.getGetOwnedAccountNumberMethod())
        val builder = Attributes.newBuilder()
        if (name != null) {
            val session = Mockito.mock(SSLSession::class.java)
            Mockito.`when`(session.peerPrincipal).thenReturn(X500Principal("CN=$name"))
            builder.set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session)
        }
        Mockito.`when`(call.attributes).thenReturn(builder.build())
    }

    @Test
    @DisplayName("원번호 RPC는 Asset 인증서만 통과시킨다")
    fun allowsAsset() {
        // given
        givenPeer("asset")
        Mockito.`when`(next.startCall(Mockito.eq(call), Mockito.any(Metadata::class.java)))
            .thenReturn(object : ServerCall.Listener<GetOwnedAccountNumberRequest>() {})
        // when
        interceptor.interceptCall(call, Metadata(), next)
        // then
        Mockito.verify(next).startCall(Mockito.eq(call), Mockito.any(Metadata::class.java))
    }

    @Test
    @DisplayName("CA가 같아도 Work 인증서로 원번호를 조회할 수 없다")
    fun deniesWork() {
        // given
        givenPeer("work")
        // when
        interceptor.interceptCall(call, Metadata(), next)
        // then
        val status = ArgumentCaptor.forClass(Status::class.java)
        Mockito.verify(call).close(status.capture(), Mockito.any(Metadata::class.java))
        assertThat(status.value.code).isEqualTo(Status.Code.PERMISSION_DENIED)
        Mockito.verifyNoInteractions(next)
    }

    @Test
    @DisplayName("TLS 정보가 없으면 원번호 RPC를 허용하지 않는다")
    fun deniesMissingTls() {
        // given
        givenPeer(null)
        // when
        interceptor.interceptCall(call, Metadata(), next)
        // then
        Mockito.verifyNoInteractions(next)
    }
}
