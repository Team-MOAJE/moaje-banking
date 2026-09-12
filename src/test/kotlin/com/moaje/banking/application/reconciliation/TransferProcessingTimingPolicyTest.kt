package com.moaje.banking.application.reconciliation

import com.moaje.banking.common.restclient.BankingExternalApiProperties
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("송금 PROCESSING stale 시간 정책")
class TransferProcessingTimingPolicyTest {
    @Nested
    @DisplayName("stale 기준이 최대 정상 처리 시간보다 크면")
    inner class SafeTiming {
        @Test
        @DisplayName("정상 설정으로 허용한다")
        fun acceptsSafeTiming() {
            // given: connect 3초, read 30초, 후처리 여유 30초보다 충분히 큰 stale 기준을 준비한다.
            val policy = timingPolicy(
                connectTimeoutMs = 3_000,
                readTimeoutMs = 30_000,
                safetyMarginMs = 30_000,
                staleAfterMs = 120_000,
            )

            // when & then: 정상 외부 호출이 끝날 시간을 침범하지 않으므로 설정을 허용한다.
            assertThatCode { policy.validate() }.doesNotThrowAnyException()
        }
    }

    @Nested
    @DisplayName("stale 기준이 최대 정상 처리 시간을 침범하면")
    inner class UnsafeTiming {
        @Test
        @DisplayName("합계와 같은 경계값도 거부한다")
        fun rejectsEqualBoundary() {
            // given: stale 기준이 connect + read + 안전 여유의 합과 정확히 같은 위험한 설정이다.
            val policy = timingPolicy(
                connectTimeoutMs = 3_000,
                readTimeoutMs = 30_000,
                safetyMarginMs = 30_000,
                staleAfterMs = 63_000,
            )

            // when & then: 같은 시각에 정상 처리와 대사가 경쟁할 수 있으므로 시작 단계에서 차단한다.
            assertThatIllegalArgumentException()
                .isThrownBy { policy.afterPropertiesSet() }
                .withMessageContaining("processing-stale-after-ms")
                .withMessageContaining("63000")
        }

        @Test
        @DisplayName("음수 안전 여유를 거부한다")
        fun rejectsNegativeSafetyMargin() {
            // given: 장애와 DB 후처리 지연을 오히려 차감하는 잘못된 안전 여유를 준비한다.
            val policy = timingPolicy(safetyMarginMs = -1)

            // when & then: 설정 오류를 조용히 보정하지 않고 운영자가 바로 확인할 수 있게 실패한다.
            assertThatIllegalArgumentException()
                .isThrownBy { policy.validate() }
                .withMessageContaining("processing-stale-safety-margin-ms")
        }
    }

    private fun timingPolicy(
        connectTimeoutMs: Long = 3_000,
        readTimeoutMs: Long = 30_000,
        safetyMarginMs: Long = 30_000,
        staleAfterMs: Long = 120_000,
    ): TransferProcessingTimingPolicy {
        val externalApiProperties = BankingExternalApiProperties(
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
        )
        val reconciliationProperties = TransferReconciliationProperties().apply {
            processingStaleSafetyMarginMs = safetyMarginMs
            processingStaleAfterMs = staleAfterMs
        }
        return TransferProcessingTimingPolicy(externalApiProperties, reconciliationProperties)
    }
}
