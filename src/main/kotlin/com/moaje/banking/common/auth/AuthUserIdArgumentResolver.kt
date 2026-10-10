package com.moaje.banking.common.auth

import com.moaje.banking.transfer.domain.PrincipalId
import com.moaje.banking.transfer.support.UnauthenticatedPrincipalException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter

import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

@Component
class AuthUserIdArgumentResolver : HandlerMethodArgumentResolver {

    // 어떤 파라미터에 이 resolver를 적용할 것인가
    // AuthUserId 어노테이션이 붙어있는 파라미터에 적용
    override fun supportsParameter(parameter: MethodParameter): Boolean {
        return parameter.hasParameterAnnotation(AuthUserId::class.java)
    }

    // 실제 파라미터에 바인딩 할 값을 생성해서 반환하도록함
    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?
    ): Any? {

        val request = webRequest.getNativeRequest(HttpServletRequest::class.java)
            ?: throw IllegalStateException("httpServletRequest를 찾을 수 없습니다")

        // 앞단 gatewayFilter에서 넣은 principal 꺼내기
        val principal = request.userPrincipal
        val annotation = parameter.getParameterAnnotation(AuthUserId::class.java)!!

        if(principal == null) {
            if(annotation.required) throw UnauthenticatedPrincipalException()
            return null
        }

        return PrincipalId(principal.name)
    }

}