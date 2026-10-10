package com.moaje.banking.common.auth

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class AuthUserId(
    val required: Boolean = true
)