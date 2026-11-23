package com.miniredis.server.command

import com.miniredis.server.protocol.RespValue
import com.miniredis.server.storage.RedisDatabase
import org.junit.jupiter.api.DisplayName
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandDispatcherTest {
    private var currentTimeMillis: Long = 1_000_000L
    private lateinit var dispatcher: CommandDispatcher

    @BeforeTest
    fun setUp() {
        currentTimeMillis = 1_000_000L
        val database = RedisDatabase(clock = { currentTimeMillis })
        dispatcher = CommandDispatcher(database)
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

    @Test
    @DisplayName("INCR, DECR, INCRBY, DECRBY 명령어로 숫자 값을 증감시킬 수 있다")
    fun incrAndDecrCommands() {
        // 없는 키에 INCR 호출 시 0 -> 1
        assertEquals(RespValue.Integer(1L), dispatcher.dispatch(commandOf("INCR", "counter")))
        assertEquals(RespValue.Integer(2L), dispatcher.dispatch(commandOf("INCR", "counter")))
        assertEquals(RespValue.Integer(12L), dispatcher.dispatch(commandOf("INCRBY", "counter", "10")))
        assertEquals(RespValue.Integer(11L), dispatcher.dispatch(commandOf("DECR", "counter")))
        assertEquals(RespValue.Integer(6L), dispatcher.dispatch(commandOf("DECRBY", "counter", "5")))
        assertEquals(RespValue.BulkString("6"), dispatcher.dispatch(commandOf("GET", "counter")))
    }

    @Test
    @DisplayName("숫자가 아닌 값이 저장된 키에 INCR을 호출하면 에러를 반환한다")
    fun incrOnNonIntegerValue() {
        dispatcher.dispatch(commandOf("SET", "name", "ydbb"))

        val response = dispatcher.dispatch(commandOf("INCR", "name"))
        assertEquals(
            RespValue.Error("ERR value is not an integer or out of range"),
            response
        )
    }

    @Test
    @DisplayName("EXPIRE, PEXPIRE, TTL, PTTL, PERSIST 명령어로 키의 유효기간을 제어할 수 있다")
    fun expireTtlAndPersistCommands() {
        dispatcher.dispatch(commandOf("SET", "otp", "998877"))
        assertEquals(RespValue.Integer(-1L), dispatcher.dispatch(commandOf("TTL", "otp")))

        // EXPIRE otp 30 -> :1
        assertEquals(RespValue.Integer(1L), dispatcher.dispatch(commandOf("EXPIRE", "otp", "30")))
        assertEquals(RespValue.Integer(30L), dispatcher.dispatch(commandOf("TTL", "otp")))
        assertEquals(RespValue.Integer(30_000L), dispatcher.dispatch(commandOf("PTTL", "otp")))

        // 10초 경과
        currentTimeMillis += 10_000L
        assertEquals(RespValue.Integer(20L), dispatcher.dispatch(commandOf("TTL", "otp")))

        // PERSIST otp -> :1 (영구 키로 전환)
        assertEquals(RespValue.Integer(1L), dispatcher.dispatch(commandOf("PERSIST", "otp")))
        assertEquals(RespValue.Integer(-1L), dispatcher.dispatch(commandOf("TTL", "otp")))

        // PEXPIRE로 500ms 설정 후 500ms 경과 시 만료되어 GET은 NULL, TTL은 -2 반환
        assertEquals(RespValue.Integer(1L), dispatcher.dispatch(commandOf("PEXPIRE", "otp", "500")))
        currentTimeMillis += 500L
        assertEquals(RespValue.NULL, dispatcher.dispatch(commandOf("GET", "otp")))
        assertEquals(RespValue.Integer(-2L), dispatcher.dispatch(commandOf("TTL", "otp")))
    }

    @Test
    @DisplayName("SET 명령어에 EX 또는 PX 옵션을 함께 전달하여 저장과 동시에 만료 시간을 설정할 수 있다")
    fun setWithExAndPxOptions() {
        assertEquals(
            RespValue.OK,
            dispatcher.dispatch(commandOf("SET", "session:1", "token-a", "EX", "60"))
        )
        assertEquals(RespValue.Integer(60L), dispatcher.dispatch(commandOf("TTL", "session:1")))

        assertEquals(
            RespValue.OK,
            dispatcher.dispatch(commandOf("SET", "session:2", "token-b", "PX", "1500"))
        )
        assertEquals(RespValue.Integer(1500L), dispatcher.dispatch(commandOf("PTTL", "session:2")))

        currentTimeMillis += 1500L
        assertEquals(RespValue.NULL, dispatcher.dispatch(commandOf("GET", "session:2")))
        assertEquals(RespValue.BulkString("token-a"), dispatcher.dispatch(commandOf("GET", "session:1")))
    }
}

