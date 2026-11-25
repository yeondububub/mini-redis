package com.miniredis.server.storage

import org.junit.jupiter.api.DisplayName
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RedisDatabaseTest {
    private var currentTimeMillis: Long = 1_000_000L
    private lateinit var database: RedisDatabase

    @BeforeTest
    fun setUp() {
        currentTimeMillis = 1_000_000L
        database = RedisDatabase(clock = { currentTimeMillis })
    }

    @Test
    @DisplayName("키와 값을 저장하고 조회할 수 있으며 없는 키는 null을 반환한다")
    fun setAndGet() {
        database.set("username", "ydbb")
        assertEquals("ydbb", database.get("username"))

        // 덮어쓰기 확인
        database.set("username", "kotlin-redis")
        assertEquals("kotlin-redis", database.get("username"))

        // 존재하지 않는 키 조회
        assertNull(database.get("non-existent"))
    }

    @Test
    @DisplayName("여러 키를 삭제할 때 실제로 존재하여 삭제된 키의 개수만 반환한다")
    fun deleteKeys() {
        database.set("k1", "v1")
        database.set("k2", "v2")

        val deletedCount = database.del(listOf("k1", "k2", "k3"))
        assertEquals(2L, deletedCount)
        assertNull(database.get("k1"))
        assertNull(database.get("k2"))
    }

    @Test
    @DisplayName("전달한 키 목록 중 존재하는 키의 개수를 정확히 반환한다")
    fun existsKeys() {
        database.set("k1", "v1")
        database.set("k2", "v2")

        val existingCount = database.exists(listOf("k1", "k2", "k3"))
        assertEquals(2L, existingCount)
    }

    @Test
    @DisplayName("키에 TTL을 설정하고 만료 시간이 지나서 조회하면 소극적 만료(Lazy Expiration)에 의해 삭제되고 null을 반환한다")
    fun lazyExpirationOnGetAndTtl() {
        database.set("code", "123456")
        assertEquals(-1L, database.ttl("code")) // 만료 시간이 없는 키는 -1
        assertEquals(-2L, database.ttl("unknown")) // 존재하지 않는 키는 -2

        // 10초 만료 설정
        assertTrue(database.expire("code", 10L))
        assertEquals(10L, database.ttl("code"))
        assertEquals(10_000L, database.pttl("code"))

        // 5초 경과 시점: 아직 유효함
        currentTimeMillis += 5_000L
        assertEquals("123456", database.get("code"))
        assertEquals(5L, database.ttl("code"))

        // 추가로 5초 경과 (총 10초 경과): 만료 시점 도달!
        currentTimeMillis += 5_000L
        assertNull(database.get("code"))
        assertEquals(-2L, database.ttl("code"))
        assertEquals(0L, database.exists(listOf("code")))
    }

    @Test
    @DisplayName("PERSIST나 일반 SET으로 덮어쓰면 기존에 설정된 TTL이 해제된다")
    fun persistAndOverwriteClearsTtl() {
        database.set("token", "abc", ttlMillis = 20_000L)
        assertEquals(20L, database.ttl("token"))

        // PERSIST로 만료 시간 해제
        assertTrue(database.persist("token"))
        assertEquals(-1L, database.ttl("token"))
        assertFalse(database.persist("token")) // 이미 TTL이 없으므로 false

        // 다시 TTL이 있는 상태로 저장 후 일반 SET으로 덮어쓰면 TTL이 사라져야 함
        database.set("token", "abc", ttlMillis = 20_000L)
        database.set("token", "xyz")
        assertEquals(-1L, database.ttl("token"))
    }

    @Test
    @DisplayName("적극적 만료(Active Expiration) 사이클이 실행되면 조회하지 않은 만료 키들도 메모리에서 자동 삭제된다")
    fun activeExpirationCleansUpUnusedExpiredKeys() {
        database.set("temp:1", "v1", ttlMillis = 1_000L)
        database.set("temp:2", "v2", ttlMillis = 1_000L)
        database.set("permanent", "v3")

        assertEquals(3, database.size())

        // 1초 경과 후 아무도 GET을 호출하지 않은 상태에서 activeExpireCycle 실행
        currentTimeMillis += 1_000L
        val purgedCount = database.activeExpireCycle()

        assertEquals(2, purgedCount)
        assertEquals(1, database.size())
        assertEquals("v3", database.get("permanent"))
    }

    @Test
    @DisplayName("Hash 자료구조에 필드를 저장, 조회, 삭제하고 모든 필드가 삭제되면 키가 제거된다")
    fun hashOperationsAndAutoCleanup() {
        val added = database.hset("user:1", listOf("name" to "마이", "role" to "ADMIN"))
        assertEquals(2L, added)

        val addedOnUpdate = database.hset("user:1", listOf("role" to "SUPER_ADMIN", "age" to "20"))
        assertEquals(1L, addedOnUpdate)
        assertEquals("SUPER_ADMIN", database.hget("user:1", "role"))
        assertEquals(
            listOf("name", "마이", "role", "SUPER_ADMIN", "age", "20"),
            database.hgetAll("user:1")
        )

        assertEquals(2L, database.hdel("user:1", listOf("name", "age", "unknown")))
        assertEquals(1L, database.exists(listOf("user:1")))

        assertEquals(1L, database.hdel("user:1", listOf("role")))
        assertEquals(0L, database.exists(listOf("user:1")))
    }

    @Test
    @DisplayName("서로 다른 자료구조 명령어로 같은 키에 접근하면 RedisWrongTypeException이 발생한다")
    fun wrongTypeThrowsException() {
        database.set("stringKey", "hello")
        assertFailsWith<RedisWrongTypeException> {
            database.hset("stringKey", listOf("field" to "val"))
        }

        database.hset("hashKey", listOf("field" to "val"))
        assertFailsWith<RedisWrongTypeException> {
            database.get("hashKey")
        }
    }
}

