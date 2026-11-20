package com.miniredis.spring

import com.miniredis.server.MiniRedisServer
import com.miniredis.spring.service.RedisCacheService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
class SpringRedisIntegrationTest {

    @Autowired
    private lateinit var redisCacheService: RedisCacheService

    @Test
    @DisplayName("스프링 부트가 직접 만든 MiniRedisServer에 연결되어 PING-PONG을 주고받는다")
    fun springPingToMiniRedis() {
        val pong = redisCacheService.ping()
        assertEquals("PONG", pong)
    }

    @Test
    @DisplayName("StringRedisTemplate을 통해 한글 및 문자열 데이터를 저장하고 조회할 수 있다")
    fun setAndGetWithStringRedisTemplate() {
        redisCacheService.set("user:1:name", "사쿠라지마 마이")
        redisCacheService.set("spring:greeting", "Hello MiniRedis from Spring Boot!")

        assertEquals("사쿠라지마 마이", redisCacheService.get("user:1:name"))
        assertEquals("Hello MiniRedis from Spring Boot!", redisCacheService.get("spring:greeting"))
        assertNull(redisCacheService.get("user:999:name"))
    }

    @Test
    @DisplayName("StringRedisTemplate으로 키 존재 여부 확인(EXISTS) 및 삭제(DEL)가 정상 동작한다")
    fun existsAndDeleteWithStringRedisTemplate() {
        val key = "session:token:abc"
        redisCacheService.set(key, "secret-jwt-token")

        assertTrue(redisCacheService.exists(key))
        assertTrue(redisCacheService.delete(key))
        assertFalse(redisCacheService.exists(key))
        assertNull(redisCacheService.get(key))
    }

    @Test
    @DisplayName("StringRedisTemplate의 increment와 decrement로 카운터를 원자적으로 증감시킬 수 있다")
    fun incrementAndDecrementWithStringRedisTemplate() {
        val key = "article:100:views"
        redisCacheService.delete(key)

        assertEquals(1L, redisCacheService.increment(key))
        assertEquals(2L, redisCacheService.increment(key))
        assertEquals(12L, redisCacheService.increment(key, 10L))
        assertEquals(11L, redisCacheService.decrement(key))
        assertEquals(6L, redisCacheService.decrement(key, 5L))
        assertEquals("6", redisCacheService.get(key))
    }

    companion object {
        private val miniRedisServer = MiniRedisServer(port = 0)
        private var serverThread: Thread? = null

        @Synchronized
        private fun ensureServerStarted() {
            if (miniRedisServer.isRunning) return
            serverThread = thread(start = true, isDaemon = true) {
                miniRedisServer.start()
            }
            while (!miniRedisServer.isRunning) {
                Thread.sleep(10)
            }
        }

        @JvmStatic
        @BeforeAll
        fun startMiniRedis() {
            ensureServerStarted()
        }

        @JvmStatic
        @AfterAll
        fun stopMiniRedis() {
            miniRedisServer.stop()
            serverThread?.join(1000)
        }

        @JvmStatic
        @DynamicPropertySource
        fun registerRedisProperties(registry: DynamicPropertyRegistry) {
            ensureServerStarted()
            registry.add("spring.data.redis.host") { "127.0.0.1" }
            registry.add("spring.data.redis.port") { miniRedisServer.boundPort }
        }
    }
}
