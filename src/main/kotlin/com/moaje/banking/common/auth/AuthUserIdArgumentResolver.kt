package com.moaje.banking.common.auth

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

    // @AuthUserId가 붙은 문자열 파라미터에만 인증 사용자 ID를 전달한다.
    override fun supportsParameter(parameter: MethodParameter): Boolean {
        return parameter.hasParameterAnnotation(AuthUserId::class.java) &&
            parameter.parameterType == String::class.java
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

        // MVC에는 문자열을 전달하고, 업무용 PrincipalId 변환은 Controller에서 한다.
        // value class를 MVC 메서드 인자로 직접 전달할 때 발생했던 타입 충돌을 피한다.
        return principal.name
    }

}
