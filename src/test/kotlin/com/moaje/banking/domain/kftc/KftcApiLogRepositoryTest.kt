package com.moaje.banking.domain.kftc

import com.moaje.banking.repository.kftc.KftcApiLogRepository
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DataJpaTest
class KftcApiLogRepositoryTest(
    @Autowired private val repository: KftcApiLogRepository,
) {
    @Test
    @DisplayName("KFTC API 로그를 거래 ID로 조회한다")
    fun findByTransferId() {
        val saved = repository.save(
            KftcApiLog(
                transferId = 100L,
                externalRefNo = "KFTC-REF-1",
                requestPayload = "{}",
                responseCode = "0000",
                status = KftcApiLogStatus.SUCCESS,
            ),
        )

        val logs = repository.findByTransferId(100L)

        assertNotNull(saved.logId)
        assertEquals(1, logs.size)
        assertEquals("KFTC-REF-1", logs.first().externalRefNo)
    }

    @Test
    @DisplayName("KFTC 외부 추적번호 존재 여부를 확인한다")
    fun existsByExternalRefNo() {
        repository.save(
            KftcApiLog(
                transferId = 101L,
                externalRefNo = "KFTC-REF-2",
                requestPayload = "{}",
            ),
        )

        assertTrue(repository.existsByExternalRefNo("KFTC-REF-2"))
    }
}
