package com.moaje.banking.application.outbox

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "moaje.banking.outbox.publisher")
class BankingOutboxPublisherProperties {
    var enabled: Boolean = true
    var batchSize: Int = 20
    var batchRepeats: Int = 5
    var leaseDurationMs: Long = 30_000
    var maxAttempts: Int = 10
    var baseBackoffMs: Long = 1_000
    var maxBackoffMs: Long = 60_000
    var kafkaSendTimeoutMs: Long = 10_000
}
