package com.moaje.banking.common.redis

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer

@Configuration
class RedisConfig {

    @Bean
    fun redisTemplate(connectionFactory: RedisConnectionFactory) : RedisTemplate<String, Any> {

        val template = RedisTemplate<String,Any>()
        template.connectionFactory = connectionFactory

        // key는 String으로 직렬화 (Redis에서 key를 깔끔하게 보기위해서)
        template.keySerializer = StringRedisSerializer()

        // value는 JSON으로 직렬화 (TokenResponse 객체를 JSON으로 저장)
        template.valueSerializer = Jackson2JsonRedisSerializer(Any::class.java)

        return template
    }
}