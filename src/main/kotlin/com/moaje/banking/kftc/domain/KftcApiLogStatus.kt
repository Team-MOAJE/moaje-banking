package com.moaje.banking.kftc.domain

enum class KftcApiLogStatus {
    SENT,
    SUCCESS,
    TIMEOUT,
    REVERSED,
    FAILED,
    // 명확한 상태구분을 통해 대사목록으로 남겨 복구할지를 구분하기 위한 상태값
    UNREADABLE_RESPONSE, // 응답을 받을 수 없음
    UNCONFIRMED_HTTP_ERROR // HTTP 오류
}
