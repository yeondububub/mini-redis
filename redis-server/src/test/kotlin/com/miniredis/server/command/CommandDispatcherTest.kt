package com.miniredis.server.command

import com.miniredis.server.protocol.RespValue
import org.junit.jupiter.api.DisplayName
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandDispatcherTest {
    private lateinit var dispatcher: CommandDispatcher

    @BeforeTest
    fun setUp() {
        dispatcher = CommandDispatcher()
    }

    private fun commandOf(vararg parts: String): RespValue.Array =
        RespValue.Array(parts.map { RespValue.BulkString(it) })

    @Test
    @DisplayName("Lettuce가 HELLO 3을 보내면 RESP2 폴백을 위해 NOPROTO 에러를 반환한다")
    fun helloHandshake() {
        val response = dispatcher.dispatch(commandOf("HELLO", "3"))
        assertEquals(RespValue.Error("NOPROTO unsupported protocol version"), response)
    }

    @Test
    @DisplayName("CLIENT, SELECT, INFO 등 클라이언트 초기 설정 명령어에 정상 응답한다")
    fun clientSelectAndInfoHandshake() {
        assertEquals(
            RespValue.OK,
            dispatcher.dispatch(commandOf("CLIENT", "SETINFO", "lib-name", "Lettuce"))
        )
        assertEquals(
            RespValue.OK,
            dispatcher.dispatch(commandOf("SELECT", "0"))
        )

        val infoResponse = dispatcher.dispatch(commandOf("INFO", "server"))
        assertTrue(infoResponse is RespValue.BulkString)
        assertTrue(infoResponse.value!!.contains("redis_version:7.0.0-mini-redis"))
    }

    @Test
    @DisplayName("DBSIZE와 FLUSHDB 명령어로 저장소 크기 확인 및 전체 초기화를 수행한다")
    fun dbSizeAndFlushDb() {
        dispatcher.dispatch(commandOf("SET", "k1", "v1"))
        dispatcher.dispatch(commandOf("SET", "k2", "v2"))

        assertEquals(RespValue.Integer(2L), dispatcher.dispatch(commandOf("DBSIZE")))
        assertEquals(RespValue.OK, dispatcher.dispatch(commandOf("FLUSHDB")))
        assertEquals(RespValue.Integer(0L), dispatcher.dispatch(commandOf("DBSIZE")))
    }
}

