package com.moaje.banking.application.outbox

import com.moaje.banking.application.event.BankingTransferEventPublisher
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.ApplicationEventPublisher
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertTrue

@SpringBootTest
@ActiveProfiles("test")
class BankingOutboxFastListenerIntegrationTest {

    @MockBean // 실제 객체의 동작은 이 Test 범위에서는 필요 없으므로 MockBean
    private lateinit var bankingOutboxPublisher: BankingOutboxPublisher

    @Autowired
    private lateinit var applicationEventPublisher: ApplicationEventPublisher

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate


    @BeforeEach
    fun setup(){
        transactionTemplate = TransactionTemplate(transactionManager)
    }

    @Test
    @DisplayName("Commit 후 Publisher를 호출한다.")
    fun shouldRunAfterCommit() {
        // given
        val eventId = "event-100"
        val eventOutbox = BankingTransferEventPublisher.TransferOutboxEvent(eventId)

        // when
        // 하나의 트랜잭션에서 EventPublisher가 이벤트를 publish 했더라도, 즉시발행되지 않고
        // 리스너에 붙어있던 AFTER_COMMIT 을 보고 아래의 트랜잭션이 끝난(commit) 이후에 발행되는지를 보겠다.
        transactionTemplate.executeWithoutResult {
            applicationEventPublisher.publishEvent(eventOutbox)
        }

        // then
        Mockito.verify(bankingOutboxPublisher,Mockito.timeout(1_000).times(1))
            .fastPublishBySingle(eventId)

    }

    @Test
    @DisplayName("Commit 전에는 Publisher를 호출하지 않는다")
    fun shouldWaitForCommit(){

        // given
        val eventId = "event-200"
        val eventOutbox = BankingTransferEventPublisher.TransferOutboxEvent(eventId)

        // when
        transactionTemplate.executeWithoutResult {
            applicationEventPublisher.publishEvent(eventOutbox)

            // 아직 Transaction이 종료되지 않았기 때문에, 리스너 위에 달아준 AFTER_COMMIT 정책에 의해 아직 퍼블리셔 메서드가 실행되지 않았는지 검증
            Mockito.verify(bankingOutboxPublisher,Mockito.never()).fastPublishBySingle(eventId)
        }

        // then
        // transactionTemplate 종료 -> Commit 이후 정상적으로 퍼블리셔 메서드가 실행되었는지 check
        Mockito.verify(bankingOutboxPublisher,Mockito.timeout(1_000).times(1))
            .fastPublishBySingle(eventId)
    }

    @Test
    @DisplayName("Rollback되면 Publisher를 호출하지 않는다")
    fun shouldSkipAfterRollback() {
        // given
        val eventId = "event-300"
        val eventOutbox = BankingTransferEventPublisher.TransferOutboxEvent(eventId)

        // when
        // transactionTemplate 의 짧은 transaction에서 event를 publish 하고난 직후 예외를 throw 한다.
        assertThrows<RuntimeException> {
            transactionTemplate.executeWithoutResult {
                applicationEventPublisher.publishEvent(eventOutbox)
                throw RuntimeException("force rollback")
            }
        }

        // then
        // 이벤트리스너가 비동기 동작이기에 일정한 관찰 구간이 필요함. 그래서 500ms 동안의 시간동안 관찰이후에 실행이 되었는지 after~never로 check
        Mockito.verify(bankingOutboxPublisher,Mockito.after(500).never()).fastPublishBySingle(eventId)
    }

    @Test
    @DisplayName("Transaction이 없으면 publisher를 호출하지 않는다")
    fun shouldNotPublishWithoutTransaction(){

        // given
        val eventId = "event-400"
        val eventOutbox = BankingTransferEventPublisher.TransferOutboxEvent(eventId)

        // when
        // TransactionTemplate 없이 publish 시도
        applicationEventPublisher.publishEvent(eventOutbox)

        // then
        // 트랜잭션 없이 publish 하면 eventListener가 동작하지 않아야함 (AFTER_COMMIT 모드로 동작중이라서.)
        Mockito.verify(bankingOutboxPublisher,Mockito.after(500).never()).fastPublishBySingle(eventId)
    }

    @Test
    @DisplayName("전용 Executor Thread 에서 실행한다")
    fun shouldRunInDedicatedExecutor(){
        //given
        val eventId = "event-500"
        val eventOutbox = BankingTransferEventPublisher.TransferOutboxEvent(eventId)

        val testThreadName = Thread.currentThread().name

        // 람다블록 내부에서 사용할 변수이므로, 지역변수 캡처링문제와 스레드 가시성을 위해 AtomicReference<String>() 사용
        val workerThreadName = AtomicReference<String>()
        val latch = CountDownLatch(1)

        Mockito.`when`(bankingOutboxPublisher.fastPublishBySingle(eventId))
            .thenAnswer {
                workerThreadName.set(Thread.currentThread().name)
                latch.countDown()
                true
            }

        // when
        transactionTemplate.executeWithoutResult {
            applicationEventPublisher.publishEvent(eventOutbox)
        }

        // then
        val completed = latch.await(1,TimeUnit.SECONDS)
        assertTrue(completed,"latch.await() timeout")
        assertNotEquals(testThreadName,workerThreadName.get(),
            "fastPublishBySingle() should run in dedicated thread"
        )
        assertTrue(workerThreadName.get().startsWith("banking-outbox-publish-"),)

        Mockito.verify(bankingOutboxPublisher,times(1)).fastPublishBySingle(eventId)
    }

}