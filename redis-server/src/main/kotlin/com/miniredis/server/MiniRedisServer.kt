package com.miniredis.server

import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

class MiniRedisServer(
    val port: Int = DEFAULT_PORT
) {
    @Volatile
    var isRunning: Boolean = false
        private set

    private var serverSocket: ServerSocket? = null

    fun start() {
        check(!isRunning) { "Mini Redis 서버가 이미 실행 중입니다." }

        val socket = ServerSocket(port)
        serverSocket = socket
        isRunning = true
        println("[MiniRedis] 서버가 시작되었습니다. 연결 대기 중... (port: $port)")

        while (isRunning) {
            try {
                val clientSocket = socket.accept()
                handleClient(clientSocket)
            } catch (e: SocketException) {
                if (isRunning) {
                    println("[MiniRedis] 소켓 에러 발생: ${e.message}")
                }
            }
        }
    }

    private fun handleClient(clientSocket: Socket) {
        clientSocket.use { socket ->
            println("[MiniRedis] 클라이언트 연결됨: ${socket.remoteSocketAddress}")
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        println("[MiniRedis] 서버가 종료되었습니다.")
    }

    companion object {
        const val DEFAULT_PORT = 6379
    }
}