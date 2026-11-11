package com.miniredis.spring.service

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class RedisCacheService(
    private val redisTemplate: StringRedisTemplate
) {

    fun ping(): String? =
        redisTemplate.connectionFactory?.connection?.use { connection ->
            connection.ping()
        }

    fun set(key: String, value: String) {
        redisTemplate.opsForValue().set(key, value)
    }

    fun get(key: String): String? =
        redisTemplate.opsForValue().get(key)

    fun exists(key: String): Boolean =
        redisTemplate.hasKey(key)

    fun delete(key: String): Boolean =
        redisTemplate.delete(key)
}

