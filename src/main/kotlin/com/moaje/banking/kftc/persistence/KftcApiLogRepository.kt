package com.moaje.banking.kftc.persistence

import com.moaje.banking.kftc.domain.KftcApiLog
import org.springframework.data.jpa.repository.JpaRepository

interface KftcApiLogRepository : JpaRepository<KftcApiLog, Long> {
    fun findByTransferId(transferId: Long): List<KftcApiLog>

    fun findByExternalRefNo(externalRefNo: String): KftcApiLog?

    fun existsByExternalRefNo(externalRefNo: String): Boolean
}
