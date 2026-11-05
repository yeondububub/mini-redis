package com.miniredis.server

import com.miniredis.server.protocol.RespDecoder
import com.miniredis.server.protocol.RespEncoder
import com.miniredis.server.protocol.RespValue
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
    @DisplayName("PING 명령어를 보내면 PONG 응답을 반환하고 인자가 있으면 그대로 반환한다")
    fun pingCommand() {
        Socket("127.0.0.1", server.boundPort).use { client ->
            val input = client.getInputStream().buffered()
            val output = client.getOutputStream().buffered()

            // 1. 인자 없는 PING -> +PONG
            val pingReq = RespValue.Array(RespValue.BulkString("PING"))
            output.write(RespEncoder.encodeToBytes(pingReq))
            output.flush()
            assertEquals(RespValue.PONG, RespDecoder.decode(input))

            // 2. 인자 있는 PING hello -> $5\r\nhello
            val pingWithArgReq = RespValue.Array(
                RespValue.BulkString("PING"),
                RespValue.BulkString("hello")
            )
            output.write(RespEncoder.encodeToBytes(pingWithArgReq))
            output.flush()
            assertEquals(RespValue.BulkString("hello"), RespDecoder.decode(input))
        }
    }

    @Test
    @DisplayName("ECHO 명령어를 보내면 전달한 문자열을 BulkString으로 반환한다")
    fun echoCommand() {
        Socket("127.0.0.1", server.boundPort).use { client ->
            val input = client.getInputStream().buffered()
            val output = client.getOutputStream().buffered()

            val echoReq = RespValue.Array(
                RespValue.BulkString("ECHO"),
                RespValue.BulkString("Hello world!")
            )
            output.write(RespEncoder.encodeToBytes(echoReq))
            output.flush()

            assertEquals(RespValue.BulkString("Hello world!"), RespDecoder.decode(input))
        }
    }

    @Test
    @DisplayName("알 수 없는 명령어를 보내면 ERR 에러 응답을 반환한다")
    fun unknownCommand() {
        Socket("127.0.0.1", server.boundPort).use { client ->
            val input = client.getInputStream().buffered()
            val output = client.getOutputStream().buffered()

            val unknownReq = RespValue.Array(RespValue.BulkString("FOOBAR"))
            output.write(RespEncoder.encodeToBytes(unknownReq))
            output.flush()

            assertEquals(
                RespValue.Error("ERR unknown command 'FOOBAR'"),
                RespDecoder.decode(input)
            )
        }
    }

    @Test
    @DisplayName("여러 클라이언트가 동시에 RESP 명령어를 보내도 각각 정상적으로 응답받는다")
    fun multipleClientsResp() {
        Socket("127.0.0.1", server.boundPort).use { client1 ->
            Socket("127.0.0.1", server.boundPort).use { client2 ->
                val input1 = client1.getInputStream().buffered()
                val output1 = client1.getOutputStream().buffered()

                val input2 = client2.getInputStream().buffered()
                val output2 = client2.getOutputStream().buffered()

                output1.write(
                    RespEncoder.encodeToBytes(
                        RespValue.Array(RespValue.BulkString("PING"))
                    )
                )
                output1.flush()

                output2.write(
                    RespEncoder.encodeToBytes(
                        RespValue.Array(
                            RespValue.BulkString("ECHO"),
                            RespValue.BulkString("second-client")
                        )
                    )
                )
                output2.flush()

                assertEquals(RespValue.PONG, RespDecoder.decode(input1))
                assertEquals(RespValue.BulkString("second-client"), RespDecoder.decode(input2))
            }
        }
    }
}


