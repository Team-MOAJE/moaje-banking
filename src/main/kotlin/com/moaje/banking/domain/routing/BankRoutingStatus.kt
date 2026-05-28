package com.moaje.banking.domain.routing

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

@Entity
@Table(
    name = "bank_routing_status",
    indexes = [
        Index(name = "idx_bank_routing_status_available", columnList = "is_available"),
        Index(name = "idx_bank_routing_status_updated_at", columnList = "updated_at"),
    ],
)
class BankRoutingStatus(
    @Id
    @Column(name = "bank_code", nullable = false, length = 10)
    var bankCode: String,

    @Column(name = "bank_name", nullable = false, length = 50)
    var bankName: String,

    @Column(name = "is_available", nullable = false)
    var available: Boolean = true,

    @Column(name = "maint_start_time")
    var maintenanceStartTime: LocalDateTime? = null,

    @Column(name = "maint_end_time")
    var maintenanceEndTime: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
