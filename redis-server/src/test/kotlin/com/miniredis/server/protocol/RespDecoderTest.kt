package com.miniredis.server.protocol

import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RespDecoderTest {

    @Test
    @DisplayName("SimpleString, Error, Integer를 정상적으로 디코딩한다")
    fun decodeBasicTypes() {
        assertEquals(RespValue.OK, RespDecoder.decode("+OK\r\n"))
        assertEquals(RespValue.Error("ERR unknown"), RespDecoder.decode("-ERR unknown\r\n"))
        assertEquals(RespValue.Integer(42L), RespDecoder.decode(":42\r\n"))
    }

    @Test
    @DisplayName("BulkString과 Null BulkString을 디코딩한다")
    fun decodeBulkString() {
        assertEquals(RespValue.BulkString("hello"), RespDecoder.decode("\$5\r\nhello\r\n"))
        assertEquals(RespValue.BulkString(""), RespDecoder.decode("\$0\r\n\r\n"))
        assertEquals(RespValue.NULL, RespDecoder.decode("\$-1\r\n"))
    }

    @Test
    @DisplayName("한글이나 줄바꿈이 포함된 BulkString도 바이트 길이 기준으로 정확히 디코딩한다")
    fun decodeKoreanAndMultilineBulkString() {
        // "안녕" = UTF-8 6바이트
        assertEquals(RespValue.BulkString("안녕"), RespDecoder.decode("\$6\r\n안녕\r\n"))
        // 본문 내부에 \r\n이 포함된 경우 ("ab\r\ncd" = 6바이트)
        assertEquals(RespValue.BulkString("ab\r\ncd"), RespDecoder.decode("\$6\r\nab\r\ncd\r\n"))
    }

    @Test
    @DisplayName("클라이언트가 보낸 명령어 Array를 재귀적으로 디코딩한다")
    fun decodeCommandArray() {
        val raw = "*3\r\n\$3\r\nSET\r\n\$3\r\nkey\r\n\$5\r\nvalue\r\n"
        val expected = RespValue.Array(
            RespValue.BulkString("SET"),
            RespValue.BulkString("key"),
            RespValue.BulkString("value")
        )
        assertEquals(expected, RespDecoder.decode(raw))
    }

    @Test
    @DisplayName("터미널에서 입력한 인라인 명령어(일반 텍스트)를 명령어 Array로 디코딩한다")
    fun decodeInlineCommand() {
        val expected = RespValue.Array(
            RespValue.BulkString("PING"),
            RespValue.BulkString("hello")
        )
        assertEquals(expected, RespDecoder.decode("PING hello\r\n"))
    }

    @Test
    @DisplayName("빈 스트림(EOF)이 들어오면 null을 반환한다")
    fun decodeEmptyStream() {
        assertNull(RespDecoder.decode(""))
    }
}

