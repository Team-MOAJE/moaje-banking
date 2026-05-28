package com.moaje.banking.domain.kftc

import com.moaje.banking.common.id.TsidGeneratedValue
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Lob
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(
    name = "kftc_api_log",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_kftc_api_log_external_ref_no", columnNames = ["external_ref_no"]),
    ],
    indexes = [
        Index(name = "idx_kftc_api_log_transfer_id", columnList = "transfer_id"),
        Index(name = "idx_kftc_api_log_created_at", columnList = "created_at"),
    ],
)
class KftcApiLog(
    @Id
    @TsidGeneratedValue
    @Column(name = "log_id", nullable = false)
    var logId: Long? = null,

    @Column(name = "transfer_id", nullable = false)
    var transferId: Long,

    @Column(name = "external_ref_no", length = 100)
    var externalRefNo: String? = null,

    @Lob
    @Column(name = "request_payload", nullable = false, columnDefinition = "MEDIUMTEXT")
    var requestPayload: String,

    @Column(name = "response_code", length = 10)
    var responseCode: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: KftcApiLogStatus = KftcApiLogStatus.SENT,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)
