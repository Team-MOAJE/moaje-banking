package com.moaje.banking.application.outbox

import com.moaje.banking.application.event.BankingTransferEventPublisher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.mockito.junit.jupiter.MockitoExtension


@Suppress("UNCHECKED_CAST")
@ExtendWith(MockitoExtension::class)
class BankingOutboxListenerTest {

    @Mock
    private lateinit var publisher: BankingOutboxPublisher

    @InjectMocks
    private lateinit var bankingOutboxFastListener: BankingOutboxFastListener

    @Test
    @DisplayName("1. 이벤트를 받으면 즉시 발행 컴포넌트에 위임한다")
    fun shouldDelegateEvent() {
        // given: eventID가 100인 Outbox 객체가 있다.
        val eventId = "event-100"

        // when: Listener의 메서드가 동작한다.
        bankingOutboxFastListener.handleOutboxSaved(BankingTransferEventPublisher.TransferOutboxEvent(eventId))

        // then: 이벤트 핸들러 메서드가 publisher의 fastPublishBySingle 메서드를 실행했고, 이 메서드는 event.eventId를 인자로 받아 정확히
        // 1번 호출 되었는가?
        Mockito.verify(publisher, times(1)).fastPublishBySingle(eventId)

    }

    @Test
    @DisplayName("2. 서로다른 이벤트가 발행되면 이벤트를 각각 위임한다")
    fun shouldDelegateEachEvent() {
        // given: eventID가 구분되는 2개의 Event가 있다.
        val eventId1 = "event-100"
        val eventId2 = "event-200"

        // when
        bankingOutboxFastListener.handleOutboxSaved(BankingTransferEventPublisher.TransferOutboxEvent(eventId1))
        bankingOutboxFastListener.handleOutboxSaved(BankingTransferEventPublisher.TransferOutboxEvent(eventId2))

        // then
        Mockito.verify(publisher, times(1)).fastPublishBySingle(eventId1)
        Mockito.verify(publisher, times(1)).fastPublishBySingle(eventId2)

        // 전체 호출 횟수
        Mockito.verify(publisher, times(2)).fastPublishBySingle(Mockito.anyString())
    }

    @Test
    @DisplayName("3. 동일한 이벤트도 전달 횟수 만큼 위임 및 실행 한다")
    fun shouldDelegateDuplicateEvent() {
        // given
        val eventId = "event-100"

        // when
        bankingOutboxFastListener.handleOutboxSaved(BankingTransferEventPublisher.TransferOutboxEvent(eventId))
        bankingOutboxFastListener.handleOutboxSaved(BankingTransferEventPublisher.TransferOutboxEvent(eventId))

        // then
        Mockito.verify(publisher, times(2)).fastPublishBySingle(eventId)
    }

    @Test
    @DisplayName("4. Publisher가 false를 반환해도 Listener가 재시도 하지 않는다 ")
    fun shouldNotRetryOnFalse() {
        //given
        val eventId = "event-100"
        Mockito.`when`(publisher.fastPublishBySingle(eventId)).thenReturn(false)

        // when
        bankingOutboxFastListener.handleOutboxSaved(BankingTransferEventPublisher.TransferOutboxEvent(eventId))

        // then
        Mockito.verify(publisher).fastPublishBySingle(eventId)
        assertDoesNotThrow {
            bankingOutboxFastListener.handleOutboxSaved(BankingTransferEventPublisher.TransferOutboxEvent(eventId))
        }
    }

    @Test
    @DisplayName("5. 예상하지 못한 Publisher 예외를 숨기지 않는다")
    fun shouldPropagateFailure() {

        // given
        val eventId = "event-100"
        Mockito.`when`(publisher.fastPublishBySingle(Mockito.anyString()))
            .thenThrow(IllegalStateException("publisher error"))

        // when
        val exception = assertThrows<IllegalStateException> {
            bankingOutboxFastListener.handleOutboxSaved(BankingTransferEventPublisher.TransferOutboxEvent(eventId))
        }

        // then
        assertEquals("publisher error", exception.message)
        Mockito.verify(publisher).fastPublishBySingle(eventId)
    }


}