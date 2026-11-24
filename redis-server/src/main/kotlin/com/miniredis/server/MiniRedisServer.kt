package com.miniredis.server

import com.miniredis.server.command.CommandDispatcher
import com.miniredis.server.protocol.RespDecoder
import com.miniredis.server.protocol.RespEncoder
import com.miniredis.server.storage.RedisDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * Java NIO [Selector] 기반의 싱글 스레드 이벤트 루프(I/O Multiplexing) Redis 서버.
 * 단 하나의 스레드가 모든 클라이언트의 연결 수락(OP_ACCEPT)과 데이터 읽기/처리(OP_READ)를 순차 수행합니다.
 */
class MiniRedisServer(
    val port: Int = DEFAULT_PORT,
    private val database: RedisDatabase = RedisDatabase(),
    private val commandDispatcher: CommandDispatcher = CommandDispatcher(database)
) {
    @Volatile
    var isRunning: Boolean = false
        private set

    private var serverChannel: ServerSocketChannel? = null
    private var selector: Selector? = null
    private val readBuffer = ByteBuffer.allocate(BUFFER_SIZE)

    val boundPort: Int
        get() = (serverChannel?.localAddress as? InetSocketAddress)?.port ?: port

    fun start() {
        check(!isRunning) { "Mini Redis 서버가 이미 실행 중입니다." }

        val channel = ServerSocketChannel.open().apply {
            configureBlocking(false)
            setOption(StandardSocketOptions.SO_REUSEADDR, true)
            bind(InetSocketAddress(port))
        }
        val sel = Selector.open()
        channel.register(sel, SelectionKey.OP_ACCEPT)

        serverChannel = channel
        selector = sel
        isRunning = true

        println("[MiniRedis] 싱글 스레드 이벤트 루프 서버가 시작되었습니다. (port: $boundPort, thread: ${Thread.currentThread().name})")

        runEventLoop(sel)
    }

    private fun runEventLoop(sel: Selector) {
        var lastExpireCycleMillis = System.currentTimeMillis()

        while (isRunning) {
            try {
                val readyCount = sel.select(ACTIVE_EXPIRE_INTERVAL_MS)

                val now = System.currentTimeMillis()
                if (now - lastExpireCycleMillis >= ACTIVE_EXPIRE_INTERVAL_MS) {
                    database.activeExpireCycle()
                    lastExpireCycleMillis = now
                }

                if (readyCount == 0) continue

                val iterator = sel.selectedKeys().iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    iterator.remove()

                    if (!key.isValid) continue

                    if (key.isAcceptable) {
                        handleAccept(key, sel)
                    } else if (key.isReadable) {
                        handleRead(key)
                    }
                }
            } catch (e: ClosedSelectorException) {
                break
            } catch (e: Exception) {
                if (isRunning) {
                    println("[MiniRedis] 이벤트 루프 에러 발생: ${e.message}")
                }
            }
        }
    }

    private fun handleAccept(key: SelectionKey, sel: Selector) {
        val srvChannel = key.channel() as ServerSocketChannel
        val clientChannel = srvChannel.accept() ?: return
        clientChannel.configureBlocking(false)
        // 각 클라이언트 채널마다 조각난 패킷을 모아둘 누적 버퍼(ByteArrayOutputStream)를 연결
        clientChannel.register(sel, SelectionKey.OP_READ, ByteArrayOutputStream())
        println("[MiniRedis] 클라이언트 연결됨: ${clientChannel.remoteAddress}")
    }

    private fun handleRead(key: SelectionKey) {
        val clientChannel = key.channel() as SocketChannel
        val sessionBuffer = key.attachment() as ByteArrayOutputStream
        val remoteAddress = runCatching { clientChannel.remoteAddress }.getOrNull()

        readBuffer.clear()
        val bytesRead = try {
            clientChannel.read(readBuffer)
        } catch (e: Exception) {
            closeClient(key, clientChannel, remoteAddress)
            return
        }

        if (bytesRead == -1) {
            closeClient(key, clientChannel, remoteAddress)
            return
        }
        if (bytesRead == 0) return

        readBuffer.flip()
        val chunk = ByteArray(readBuffer.remaining())
        readBuffer.get(chunk)
        sessionBuffer.write(chunk)

        processBufferedCommands(clientChannel, sessionBuffer)
    }

    /**
     * 클라이언트 누적 버퍼에 쌓인 데이터에서 완성된 RESP 명령어들을 순서대로 파싱하고 실행합니다.
     * 한 번에 여러 명령어가 들어온 경우(Pipelining) 모두 처리하고, 아직 덜 도착한 조각 데이터는 버퍼에 남겨둡니다.
     */
    private fun processBufferedCommands(
        clientChannel: SocketChannel,
        sessionBuffer: ByteArrayOutputStream
    ) {
        val input = ByteArrayInputStream(sessionBuffer.toByteArray())

        while (input.available() > 0) {
            input.mark(Int.MAX_VALUE)
            val request = try {
                RespDecoder.decode(input) ?: break
            } catch (e: IllegalArgumentException) {
                // 아직 패킷이 다 도착하지 않아 파싱이 끝까지 안 된 경우 마크 지점으로 되돌리고 다음 OP_READ 대기
                input.reset()
                break
            }

            val response = commandDispatcher.dispatch(request)
            val responseBytes = RespEncoder.encodeToBytes(response)
            writeAll(clientChannel, ByteBuffer.wrap(responseBytes))
        }

        sessionBuffer.reset()
        if (input.available() > 0) {
            sessionBuffer.write(input.readAllBytes())
        }
    }

    private fun writeAll(channel: SocketChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    private fun closeClient(key: SelectionKey, channel: SocketChannel, remoteAddress: Any?) {
        runCatching { key.cancel() }
        runCatching { channel.close() }
        println("[MiniRedis] 클라이언트 연결 종료: $remoteAddress")
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        runCatching { selector?.wakeup() }
        runCatching {
            selector?.keys()?.forEach { key ->
                runCatching { key.channel().close() }
            }
        }
        runCatching { selector?.close() }
        runCatching { serverChannel?.close() }
        selector = null
        serverChannel = null
        println("[MiniRedis] 서버가 종료되었습니다.")
    }

    companion object {
        const val DEFAULT_PORT = 6379
        private const val BUFFER_SIZE = 8192
        private const val ACTIVE_EXPIRE_INTERVAL_MS = 100L
    }
}
