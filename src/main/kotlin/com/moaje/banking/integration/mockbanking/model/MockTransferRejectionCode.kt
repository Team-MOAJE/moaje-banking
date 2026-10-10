package com.moaje.banking.integration.mockbanking.model

// 송금 요청이 실행 전에 거절됐음을 보장하는 HTTP 상태와 코드만 등록한다.
// 결과 조회용 TRANSFER_NOT_FOUND는 송금 거절 목록에 포함하지 않는다.
enum class MockTransferRejectionCode(val httpStatus: Int) {
    ACCOUNT_NOT_FOUND(404),
    ACCOUNT_CANCELED(400),
    INSUFFICIENT_BALANCE(400),
    INVALID_ACCOUNT_AMOUNT(400),
    SAME_ACCOUNT_TRANSFER(400),
    ;

    companion object {
        fun matches(httpStatus: Int, code: String): Boolean =
            entries.any { it.httpStatus == httpStatus && it.name == code }
    }
}
