package com.moaje.banking.api.grpc

import com.moaje.grpc.banking.BankingServiceGrpc
import io.grpc.Grpc
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import javax.naming.ldap.LdapName

/**
 * CA가 같은 Work 인증서도 유효하므로, mTLS 통과만으로 원번호 조회를 허용하지 않는다.
 * Infra가 발급하는 CN=asset 인증서에만 새 RPC를 허용하며 다른 RPC의 정책은 바꾸지 않는다.
 */
class OwnedAccountNumberInterceptor : ServerInterceptor {
    override fun <ReqT : Any, RespT : Any> interceptCall(
        call: ServerCall<ReqT, RespT>, headers: Metadata, next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        if (call.methodDescriptor.fullMethodName != BankingServiceGrpc.getGetOwnedAccountNumberMethod().fullMethodName) {
            return next.startCall(call, headers)
        }
        val names = runCatching {
            val peer = call.attributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION)?.peerPrincipal ?: return@runCatching emptyList<String>()
            LdapName(peer.name).rdns.filter { it.type.equals("CN", ignoreCase = true) }.map { it.value.toString() }
        }.getOrDefault(emptyList())
        if (names != listOf("asset")) {
            call.close(Status.PERMISSION_DENIED.withDescription("Asset 인증서가 필요한 RPC입니다."), Metadata())
            return object : ServerCall.Listener<ReqT>() {}
        }
        return next.startCall(call, headers)
    }
}
