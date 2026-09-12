package com.moaje.banking.infra.outbox

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableConfigurationProperties(BankingOutboxAsyncProperties::class)
class BankingOutboxAsyncConfig(
    private val properties: BankingOutboxAsyncProperties,
    private val meterRegistry: MeterRegistry
) {
    companion object {
        const val EXECUTOR_NAME = "bankingOutboxPublishExecutor"
        val LOG = org.slf4j.LoggerFactory.getLogger(javaClass)
    }

    @Bean(name = [EXECUTOR_NAME])
    fun bankingOutboxPublishExecutor(): ThreadPoolTaskExecutor {

        // 쓰레드 풀의 기본적인 데이터 set
        return ThreadPoolTaskExecutor().apply {
            corePoolSize = properties.corePoolSize
            maxPoolSize = properties.maxPoolSize
            queueCapacity = properties.queueCapacity
            keepAliveSeconds = properties.keepAlive.seconds.toInt()

            setThreadNamePrefix("banking-outbox-publish-")

            // application 종료 시 Queue에 있던 작업이 완료 될 시간을 잠시 제공(15s)
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(properties.awaitTermination.seconds.toInt())

            // 만약 Handler가 queue에 있는 데이터를 모두 처리하지 못하는 포화상태라면 우아하게 Reject 한다
            // queue가 꽉 차서 들어온 rejectedTask는 조용히 폐기
            setRejectedExecutionHandler { rejectedTask, executor ->
                meterRegistry.counter("banking.outbox.async.rejected").increment()

                // 로그는 과도하게 반복되지 않도록, 샘플링 또는 집계하는 방식도 검토
                LOG.warn("Outbox immediate publish task rejected. " +
                        " poolSize = {}, activeCount = {}, queueSize = {}",
                    executor.poolSize, executor.activeCount, executor.queue.size)
            }
        }
    }
}