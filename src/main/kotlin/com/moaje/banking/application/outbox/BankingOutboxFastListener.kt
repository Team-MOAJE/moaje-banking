package com.moaje.banking.application.outbox

import com.moaje.banking.application.event.BankingTransferEventPublisher
import com.moaje.banking.infra.outbox.BankingOutboxAsyncConfig
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener


/**
 * Transfer event 를 단건으로 받아서 즉시 publish 한다.
 */
@Component
class BankingOutboxFastListener(
    private val bankingOutboxPublisher: BankingOutboxPublisher,
) {
    // Discard Policy 정책으로, 비동기 Queue에 Task가 모두 차있는 상태라면 폐기
    @Async(BankingOutboxAsyncConfig.EXECUTOR_NAME)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun handleOutboxSaved(event: BankingTransferEventPublisher.TransferOutboxEvent) {
        //println("BankingOutboxFastListener.handleOutboxSaved 실행~!")
        bankingOutboxPublisher.fastPublishBySingle(event.eventId)

    }


}