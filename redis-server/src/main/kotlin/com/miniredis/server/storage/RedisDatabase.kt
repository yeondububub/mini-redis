package com.miniredis.server.storage

/**
 * Key-Value 데이터를 메모리에서 관리하는 핵심 저장소.
 * 싱글 스레드 이벤트 루프에서 순차적으로 호출되므로 별도의 락이나 ConcurrentHashMap 없이
 * 일반 HashMap만으로도 동시성 안전(Thread-safe)과 원자성(Atomicity)이 보장됩니다.
 */
class RedisDatabase {
    private val store = HashMap<String, String>()

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
     * 지정한 키의 정수 값을 [delta]만큼 증감시키고 결과 값을 반환합니다.
     * 키가 존재하지 않으면 0에서 시작하며, 저장된 값이 정수가 아니면 [NumberFormatException]을 던집니다.
     */
    fun incrBy(key: String, delta: Long): Long {
        val currentRaw = store[key]
        val currentValue = if (currentRaw == null) {
            0L
        } else {
            currentRaw.toLongOrNull()
                ?: throw NumberFormatException("ERR value is not an integer or out of range")
        }

        val updatedValue = Math.addExact(currentValue, delta)
        store[key] = updatedValue.toString()
        return updatedValue
    }

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

