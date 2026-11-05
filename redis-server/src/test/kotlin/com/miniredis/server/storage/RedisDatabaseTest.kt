package com.miniredis.server.storage

import org.junit.jupiter.api.DisplayName
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RedisDatabaseTest {
    private lateinit var database: RedisDatabase

    @BeforeTest
    fun setUp() {
        database = RedisDatabase()
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
}

