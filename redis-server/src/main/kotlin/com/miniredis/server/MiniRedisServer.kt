package com.miniredis.server

class MiniRedisServer(
    val port: Int = DEFAULT_PORT
) {
    @Volatile
    var isRunning: Boolean = false
        private set

    fun start() {
        check(!isRunning) { "Mini Redis 서버가 이미 실행 중입니다." }
        isRunning = true
        println("[MiniRedis] 서버가 시작 준비 중입니다... (port: $port)")
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        println("[MiniRedis] 서버가 종료되었습니다.")
    }

    companion object {
        const val DEFAULT_PORT = 6379
    }
}

