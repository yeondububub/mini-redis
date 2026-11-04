package com.miniredis.server.protocol

import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals

class RespEncoderTest {

    @Test
    @DisplayName("SimpleString을 RESP 규격으로 인코딩한다")
    fun encodeSimpleString() {
        assertEquals("+OK\r\n", RespEncoder.encode(RespValue.OK))
        assertEquals("+PONG\r\n", RespEncoder.encode(RespValue.PONG))
    }

    @Test
    @DisplayName("Error를 RESP 규격으로 인코딩한다")
    fun encodeError() {
        val error = RespValue.Error("ERR unknown command 'FOO'")
        assertEquals("-ERR unknown command 'FOO'\r\n", RespEncoder.encode(error))
    }

    @Test
    @DisplayName("Integer를 RESP 규격으로 인코딩한다")
    fun encodeInteger() {
        assertEquals(":1000\r\n", RespEncoder.encode(RespValue.Integer(1000L)))
        assertEquals(":-1\r\n", RespEncoder.encode(RespValue.Integer(-1L)))
    }

    @Test
    @DisplayName("BulkString과 Null BulkString을 RESP 규격으로 인코딩한다")
    fun encodeBulkString() {
        assertEquals("\$5\r\nhello\r\n", RespEncoder.encode(RespValue.BulkString("hello")))
        assertEquals("\$0\r\n\r\n", RespEncoder.encode(RespValue.BulkString("")))
        assertEquals("\$-1\r\n", RespEncoder.encode(RespValue.NULL))
    }

    @Test
    @DisplayName("한글이 포함된 BulkString은 UTF-8 바이트 길이를 기준으로 인코딩한다")
    fun encodeKoreanBulkString() {
        // "안녕"은 2글자이지만 UTF-8에서는 6바이트
        assertEquals("\$6\r\n안녕\r\n", RespEncoder.encode(RespValue.BulkString("안녕")))
    }

    @Test
    @DisplayName("Array를 RESP 규격으로 인코딩한다")
    fun encodeArray() {
        val command = RespValue.Array(
            RespValue.BulkString("SET"),
            RespValue.BulkString("key"),
            RespValue.BulkString("value")
        )
        val expected = "*3\r\n\$3\r\nSET\r\n\$3\r\nkey\r\n\$5\r\nvalue\r\n"
        assertEquals(expected, RespEncoder.encode(command))
    }
}


