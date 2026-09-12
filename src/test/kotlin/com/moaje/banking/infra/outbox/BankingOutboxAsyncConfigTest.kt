package com.moaje.banking.infra.outbox

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.time.Duration

@ExtendWith(MockitoExtension::class)
class BankingOutboxAsyncConfigTest {

    @Mock
    private lateinit var meterRegistry: MeterRegistry

    @Mock
    private lateinit var counter: Counter
    private lateinit var properties: BankingOutboxAsyncProperties
    private lateinit var config: BankingOutboxAsyncConfig
    private lateinit var executor: ThreadPoolTaskExecutor
    @BeforeEach
    fun setUp() {
        properties = BankingOutboxAsyncProperties(
            corePoolSize = 2,
            maxPoolSize = 4,
            queueCapacity = 10,
            keepAlive = Duration.ofSeconds(30),
            awaitTermination = Duration.ofSeconds(15),
        )

        config = BankingOutboxAsyncConfig(properties, meterRegistry)
        executor = config.bankingOutboxPublishExecutor()
    }

    @AfterEach
    fun tearDown() {
        executor.shutdown()
    }

    @Test
    @DisplayName("Pool 크기 설정을 적용한다.")
    fun shouldApplyConfigFields() {

        //then
        assertEquals(2,executor.corePoolSize,"corePoolSize test failed")
        assertEquals(4,executor.maxPoolSize,"maxPoolSize test failed")
        assertEquals(10,executor.queueCapacity,"queueCapacity test failed")
        assertEquals(30,executor.keepAliveSeconds,"keepAliveSec test failed")
    }

    @Test
    @DisplayName("Thead 이름에 접두사를 적용한다")
    fun shouldApplyThreadPrefix() {
        assertEquals("banking-outbox-publish-",executor.threadNamePrefix,"threadNamePrefix test failed")
    }

    @Test
    @DisplayName("거부 발생 횟수를 기록한다")
    fun shouldCountRejection() {

        //when
        Mockito.`when`(meterRegistry.counter("banking.outbox.async.rejected")).thenReturn(counter)
        val executor = config.bankingOutboxPublishExecutor()
        executor.initialize()

        //then
        try {
            val nativeExecutor = executor.threadPoolExecutor
            val handler = nativeExecutor.rejectedExecutionHandler
            val rejectTask = Mockito.mock(Runnable::class.java)

            handler.rejectedExecution(rejectTask, nativeExecutor)
            Mockito.verify(meterRegistry).counter("banking.outbox.async.rejected")
            Mockito.verify(counter).increment()
        }finally {
            executor.shutdown()
        }
    }





}