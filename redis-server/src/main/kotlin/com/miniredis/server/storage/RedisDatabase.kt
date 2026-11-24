package com.miniredis.server.storage

/**
 * Key-Value 데이터를 메모리에서 관리하는 핵심 저장소.
 * 싱글 스레드 이벤트 루프에서 순차적으로 호출되므로 별도의 락이나 ConcurrentHashMap 없이
 * 일반 HashMap만으로도 동시성 안전(Thread-safe)과 원자성(Atomicity)이 보장됩니다.
 */
class RedisDatabase(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val store = HashMap<String, String>()
    private val expires = HashMap<String, Long>()

    /**
     * 지정한 키에 문자열 값을 저장합니다. 이미 존재하는 키라면 덮어씁니다.
     * [ttlMillis]가 지정되면 만료 시간을 설정하고, 지정되지 않으면 기존 만료 설정을 해제합니다.
     */
    fun set(key: String, value: String, ttlMillis: Long? = null) {
        if (ttlMillis != null) {
            if (ttlMillis <= 0L) {
                store.remove(key)
                expires.remove(key)
                return
            }
            store[key] = value
            expires[key] = clock() + ttlMillis
        } else {
            store[key] = value
            expires.remove(key)
        }
    }

    /**
     * 지정한 키의 값을 조회합니다. 만료되었거나 존재하지 않으면 null을 반환합니다.
     */
    fun get(key: String): String? {
        purgeIfExpired(key)
        return store[key]
    }

    /**
     * 전달받은 키 목록을 삭제하고, 실제로 존재해서 삭제된 키의 개수를 반환합니다.
     */
    fun del(keys: List<String>): Long =
        keys.count { key ->
            purgeIfExpired(key)
            expires.remove(key)
            store.remove(key) != null
        }.toLong()

    /**
     * 전달받은 키 목록 중 현재 존재하는 키의 개수를 반환합니다.
     */
    fun exists(keys: List<String>): Long =
        keys.count { key ->
            purgeIfExpired(key)
            store.containsKey(key)
        }.toLong()

    /**
     * 지정한 키의 정수 값을 [delta]만큼 증감시키고 결과 값을 반환합니다.
     * 키가 존재하지 않으면 0에서 시작하며, 저장된 값이 정수가 아니면 [NumberFormatException]을 던집니다.
     */
    fun incrBy(key: String, delta: Long): Long {
        purgeIfExpired(key)
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
     * 지정한 키에 초(second) 단위의 만료 시간(TTL)을 설정합니다.
     * 키가 존재하여 설정에 성공하면 true, 키가 없으면 false를 반환합니다.
     */
    fun expire(key: String, ttlSeconds: Long): Boolean =
        pexpire(key, ttlSeconds * 1000L)

    /**
     * 지정한 키에 밀리초(millisecond) 단위의 만료 시간(TTL)을 설정합니다.
     * 키가 존재하여 설정에 성공하면 true, 키가 없으면 false를 반환합니다.
     */
    fun pexpire(key: String, ttlMillis: Long): Boolean {
        purgeIfExpired(key)
        if (!store.containsKey(key)) return false

        if (ttlMillis <= 0L) {
            store.remove(key)
            expires.remove(key)
            return true
        }
        expires[key] = clock() + ttlMillis
        return true
    }

    /**
     * 지정한 키의 남은 만료 시간을 초(second) 단위로 반환합니다.
     * - 키가 존재하지 않으면 -2
     * - 키는 존재하지만 만료 시간이 설정되지 않았으면 -1
     */
    fun ttl(key: String): Long {
        val remainingMillis = pttl(key)
        if (remainingMillis < 0L) return remainingMillis
        return (remainingMillis + 999L) / 1000L
    }

    /**
     * 지정한 키의 남은 만료 시간을 밀리초(millisecond) 단위로 반환합니다.
     * - 키가 존재하지 않으면 -2
     * - 키는 존재하지만 만료 시간이 설정되지 않았으면 -1
     */
    fun pttl(key: String): Long {
        purgeIfExpired(key)
        if (!store.containsKey(key)) return -2L
        val expireAt = expires[key] ?: return -1L
        return (expireAt - clock()).coerceAtLeast(0L)
    }

    /**
     * 지정한 키에 설정된 만료 시간(TTL)을 제거하여 영구 보존 상태로 만듭니다.
     * 만료 시간이 실제로 제거되었으면 true, 키가 없거나 원래 만료 시간이 없었으면 false를 반환합니다.
     */
    fun persist(key: String): Boolean {
        purgeIfExpired(key)
        if (!store.containsKey(key)) return false
        return expires.remove(key) != null
    }

    /**
     * 적극적 만료(Active Expiration):
     * 아무도 조회하지 않는 만료 키가 메모리에 계속 남는 것을 방지하기 위해,
     * 이벤트 루프가 주기적으로(기본 100ms) 만료 설정된 키 중 최대 [sampleSize]개를 샘플링하여 삭제합니다.
     * 실제 Redis 알고리즘과 동일하게 샘플 중 25% 이상이 만료되었다면 즉시 다음 샘플링을 반복합니다.
     */
    fun activeExpireCycle(sampleSize: Int = DEFAULT_EXPIRE_SAMPLE_SIZE): Int {
        if (expires.isEmpty()) return 0

        var totalPurged = 0
        while (expires.isNotEmpty()) {
            val sampledKeys = expires.keys.take(sampleSize)
            if (sampledKeys.isEmpty()) break

            val purgedInBatch = sampledKeys.count { key -> purgeIfExpired(key) }
            totalPurged += purgedInBatch

            // 만료된 키 비율이 25% 이하이면 이번 사이클 종료
            if (purgedInBatch * 4 <= sampledKeys.size) {
                break
            }
        }
        return totalPurged
    }

    /**
     * 소극적 만료(Lazy Expiration):
     * 키에 접근하는 시점에 만료 시각이 지났는지 확인하고, 지났다면 즉시 메모리에서 삭제합니다.
     */
    private fun purgeIfExpired(key: String): Boolean {
        val expireAt = expires[key] ?: return false
        if (clock() >= expireAt) {
            store.remove(key)
            expires.remove(key)
            return true
        }
        return false
    }

    /**
     * 저장소의 모든 데이터를 초기화합니다.
     */
    fun clear() {
        store.clear()
        expires.clear()
    }

    /**
     * 현재 저장된 키의 전체 개수를 반환합니다.
     */
    fun size(): Int = store.size

    companion object {
        private const val DEFAULT_EXPIRE_SAMPLE_SIZE = 20
    }
}

