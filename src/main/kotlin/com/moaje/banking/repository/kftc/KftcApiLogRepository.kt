package com.moaje.banking.repository.kftc

import com.moaje.banking.domain.kftc.KftcApiLog
import org.springframework.data.jpa.repository.JpaRepository

interface KftcApiLogRepository : JpaRepository<KftcApiLog, Long> {
    fun findByTransferId(transferId: Long): List<KftcApiLog>

    fun findByExternalRefNo(externalRefNo: String): KftcApiLog?

    fun existsByExternalRefNo(externalRefNo: String): Boolean
}
