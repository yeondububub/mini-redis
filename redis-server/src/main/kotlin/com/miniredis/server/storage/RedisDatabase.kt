package com.miniredis.server.storage

import java.util.concurrent.ConcurrentHashMap

/**
 * Key-Value 데이터를 메모리에서 관리하는 핵심 저장소.
 */
class RedisDatabase {
    private val store = ConcurrentHashMap<String, String>()

    /**
     * 지정한 키에 문자열 값을 저장합니다. 이미 존재하는 키라면 덮어씁니다.
     */
    fun set(key: String, value: String) {
        store[key] = value
    }

    /**
     * 지정한 키의 값을 조회합니다. 존재하지 않으면 null을 반환합니다.
     */
    fun get(key: String): String? = store[key]

    /**
     * 전달받은 키 목록을 삭제하고, 실제로 존재해서 삭제된 키의 개수를 반환합니다.
     */
    fun del(keys: List<String>): Long =
        keys.count { key -> store.remove(key) != null }.toLong()

    /**
     * 전달받은 키 목록 중 현재 존재하는 키의 개수를 반환합니다.
     */
    fun exists(keys: List<String>): Long =
        keys.count { key -> store.containsKey(key) }.toLong()

    /**
     * 저장소의 모든 데이터를 초기화합니다.
     */
    fun clear() {
        store.clear()
    }

    /**
     * 현재 저장된 키의 전체 개수를 반환합니다.
     */
    fun size(): Int = store.size
}

