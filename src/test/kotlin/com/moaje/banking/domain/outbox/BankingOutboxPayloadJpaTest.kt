package com.moaje.banking.domain.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import java.util.UUID

@DataJpaTest
class BankingOutboxPayloadJpaTest(@Autowired private val entityManager: TestEntityManager) {
    @Test
    @DisplayName("255바이트를 넘는 Outbox 본문도 변경 없이 저장하고 조회한다")
    fun preservesBinaryPayloadBeyondTinyBlobSize() {
        // given: MySQL TINYBLOB 범위보다 큰 본문으로 이벤트의 잘림이나 바이트 변형을 확인한다.
        val payload = ByteArray(1024) { (it % 256).toByte() }
        val event = BankingOutboxEvent(
            eventId = UUID.randomUUID().toString(),
            aggregateType = "TEST",
            aggregateId = "test-transfer",
            eventType = "TEST_COMPLETED",
            topic = "test-topic",
            messageKey = "test-account",
            payload = payload,
        )

        // when: 영속성 컨텍스트를 비워 메모리 객체가 아니라 실제 DB 저장 결과를 다시 읽는다.
        entityManager.persistAndFlush(event)
        entityManager.clear()
        val restored = entityManager.find(BankingOutboxEvent::class.java, event.eventId)

        // then: H2 왕복 저장을 검증하며, MySQL의 실제 타입 검증은 Compose 기동 테스트가 별도로 담당한다.
        assertThat(restored.payload).containsExactly(*payload)
    }
}
