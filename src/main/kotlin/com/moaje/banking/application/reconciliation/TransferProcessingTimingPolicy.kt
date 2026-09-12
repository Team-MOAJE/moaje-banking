package com.moaje.banking.application.reconciliation

import com.moaje.banking.common.restclient.BankingExternalApiProperties
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Component

@Component
class TransferProcessingTimingPolicy(
    private val externalApiProperties: BankingExternalApiProperties,
    private val reconciliationProperties: TransferReconciliationProperties,
) : InitializingBean {
    override fun afterPropertiesSet() {
        validate()
    }

    /**
     * 정상 HTTP 호출이 끝날 수 있는 시간보다 stale 판정이 먼저 오면, 아직 송금 중인 거래를 대사 Worker가 선점할 수 있다.
     * 그래서 연결·응답 대기 시간에 DB 반영과 실행 지연을 위한 안전 여유를 더한 값보다 stale 기준이 반드시 크게 설정되도록 막는다.
     */
    fun validate() {
        require(externalApiProperties.connectTimeoutMs in 1..Int.MAX_VALUE.toLong()) {
            "moaje.banking.external-api.connect-timeout-ms는 1 이상 ${Int.MAX_VALUE} 이하여야 합니다."
        }
        require(externalApiProperties.readTimeoutMs in 1..Int.MAX_VALUE.toLong()) {
            "moaje.banking.external-api.read-timeout-ms는 1 이상 ${Int.MAX_VALUE} 이하여야 합니다."
        }
        require(reconciliationProperties.processingStaleSafetyMarginMs >= 0) {
            "moaje.banking.reconciliation.transfer.processing-stale-safety-margin-ms는 0 이상이어야 합니다."
        }

        val minimumExclusiveStaleAfterMs = Math.addExact(
            Math.addExact(externalApiProperties.connectTimeoutMs, externalApiProperties.readTimeoutMs),
            reconciliationProperties.processingStaleSafetyMarginMs,
        )

        require(reconciliationProperties.processingStaleAfterMs > minimumExclusiveStaleAfterMs) {
            "moaje.banking.reconciliation.transfer.processing-stale-after-ms" +
                "(${reconciliationProperties.processingStaleAfterMs})는 connect-timeout-ms" +
                "(${externalApiProperties.connectTimeoutMs}) + read-timeout-ms" +
                "(${externalApiProperties.readTimeoutMs}) + processing-stale-safety-margin-ms" +
                "(${reconciliationProperties.processingStaleSafetyMarginMs})보다 커야 합니다."
        }
    }
}
