package com.miniredis.server

import org.junit.jupiter.api.DisplayName
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MiniRedisServerTest {
    private lateinit var server: MiniRedisServer
    private lateinit var serverThread: Thread

    @BeforeTest
    fun setUp() {
        // port = 0 으로 설정하면 OS가 비어있는 포트를 자동으로 할당합니다.
        server = MiniRedisServer(port = 0)
        serverThread = thread(start = true, isDaemon = true) {
            server.start()
        }
        // 서버가 시작될 때까지 잠시 대기
        while (!server.isRunning) {
            Thread.sleep(10)
        }
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        serverThread.join(1000)
    }

    @Test
    @DisplayName("클라이언트가 메시지를 보내면 +메시지 형태로 에코 응답을 반환한다")
    fun test1() {
        Socket("127.0.0.1", server.boundPort).use { client ->
            val writer = client.getOutputStream().bufferedWriter(Charsets.UTF_8)
            val reader = client.getInputStream().bufferedReader(Charsets.UTF_8)

            writer.write("hello\r\n")
            writer.flush()

            val response = reader.readLine()
            assertEquals("+hello", response)
        }
    }

    @Test
    @DisplayName("여러 클라이언트가 동시에 연결되어도 각각 정상적으로 응답을 주고받는다")
    fun test2() {
        Socket("127.0.0.1", server.boundPort).use { client1 ->
            Socket("127.0.0.1", server.boundPort).use { client2 ->
                val writer1 = client1.getOutputStream().bufferedWriter(Charsets.UTF_8)
                val reader1 = client1.getInputStream().bufferedReader(Charsets.UTF_8)

                val writer2 = client2.getOutputStream().bufferedWriter(Charsets.UTF_8)
                val reader2 = client2.getInputStream().bufferedReader(Charsets.UTF_8)

                writer1.write("first-client\r\n")
                writer1.flush()

                writer2.write("second-client\r\n")
                writer2.flush()

                assertEquals("+first-client", reader1.readLine())
                assertEquals("+second-client", reader2.readLine())
            }
        }
    }
}

