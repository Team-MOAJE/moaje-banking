package com.moaje.banking.common.id

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdGeneratorTest {
    @Test
    @DisplayName("내부 PK로 사용할 TSID Long 값을 생성한다")
    fun nextId() {
        val generator = IdGenerator()

        val firstId = generator.nextId()
        val secondId = generator.nextId()

        assertTrue(firstId > 0)
        assertTrue(secondId > 0)
        assertNotEquals(firstId, secondId)
    }

    @Test
    @DisplayName("외부 노출용 송금 ID를 MOAJE-BNK-yyyyMMdd-base32(tsid) 형식으로 생성한다")
    fun nextBankingTransferPublicId() {
        val generator = IdGenerator()

        val publicId = generator.nextBankingTransferPublicId(LocalDate.of(2026, 5, 25))
        val parts = publicId.split("-")

        assertEquals(4, parts.size)
        assertEquals("MOAJE", parts[0])
        assertEquals("BNK", parts[1])
        assertEquals("20260525", parts[2])
        assertEquals(13, parts[3].length)
        assertTrue(parts[3].all { it in '0'..'9' || it in 'A'..'Z' })
    }
}
