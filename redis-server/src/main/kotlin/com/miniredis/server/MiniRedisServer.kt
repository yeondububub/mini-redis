package com.miniredis.server

import com.miniredis.server.command.CommandDispatcher
import com.miniredis.server.protocol.RespDecoder
import com.miniredis.server.protocol.RespEncoder
import com.miniredis.server.storage.RedisDatabase
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors

class MiniRedisServer(
    val port: Int = DEFAULT_PORT,
    private val database: RedisDatabase = RedisDatabase(),
    private val commandDispatcher: CommandDispatcher = CommandDispatcher(database)
) {
    @Volatile
    var isRunning: Boolean = false
        private set

    private var serverSocket: ServerSocket? = null
    private val clientExecutor = Executors.newVirtualThreadPerTaskExecutor()

    val boundPort: Int
        get() = serverSocket?.localPort ?: port

    fun start() {
        check(!isRunning) { "Mini Redis 서버가 이미 실행 중입니다." }

        val socket = ServerSocket(port).apply {
            reuseAddress = true
        }
        serverSocket = socket
        isRunning = true
        println("[MiniRedis] 서버가 시작되었습니다. 연결 대기 중... (port: $boundPort)")

        while (isRunning) {
            try {
                val clientSocket = socket.accept()
                clientExecutor.submit {
                    handleClient(clientSocket)
                }
            } catch (e: SocketException) {
                if (isRunning) {
                    println("[MiniRedis] 소켓 에러 발생: ${e.message}")
                }
            }
        }
    }

    private fun handleClient(clientSocket: Socket) {
        val remoteAddress = clientSocket.remoteSocketAddress
        println("[MiniRedis] 클라이언트 연결됨: $remoteAddress")

        try {
            clientSocket.use { socket ->
                val input = socket.getInputStream().buffered()
                val output = socket.getOutputStream().buffered()

                while (isRunning) {
                    val request = RespDecoder.decode(input) ?: break
                    val response = commandDispatcher.dispatch(request)
                    output.write(RespEncoder.encodeToBytes(response))
                    output.flush()
                }
            }
        } catch (e: Exception) {
            if (isRunning) {
                println("[MiniRedis] 클라이언트 통신 중 에러 ($remoteAddress): ${e.message}")
            }
        } finally {
            println("[MiniRedis] 클라이언트 연결 종료: $remoteAddress")
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        runCatching { serverSocket?.close() }
        clientExecutor.shutdownNow()
        serverSocket = null
        println("[MiniRedis] 서버가 종료되었습니다.")
    }

    companion object {
        const val DEFAULT_PORT = 6379
    }
}
