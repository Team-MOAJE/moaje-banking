package com.moaje.banking.infra.outbox

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 *
 * 2026.08.31
 * 각 configProperties field 값은 초기수치이므로 추 후 테스트와 측정을 통해
 * 값의 변경이 이뤄질 수 있음. 초기수치를 크게 잡으면 측정이 어려울 수 있어서 작은 값부터 시작함.
 * 필요 워커 수 = 초당 이벤트 수 x 이벤트 한 건의 평균 처리시간(초)
 * 현재 기준은 초당 50건의 event 발생, 한 건당 800ms(0.08초) 라는 가정 하에 잡은 기준.
 * Queue 사이즈 결정 = Queue 순간유입량 * 흡수가능한 시간
 *
 **/
@ConfigurationProperties(prefix = "moaje.banking.outbox.async")
data class BankingOutboxAsyncProperties(
    val corePoolSize: Int = 4,
    val maxPoolSize: Int = 8,
    val queueCapacity: Int = 200,
    val keepAlive: Duration = Duration.ofSeconds(60),
    val awaitTermination: Duration = Duration.ofSeconds(15)
)
