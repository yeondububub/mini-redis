package com.miniredis.server.protocol

/**
 * [RespValue] 객체를 Redis RESP 프로토콜 규격의 문자열(또는 바이트 배열)로 직렬화하는 인코더.
 */
object RespEncoder {
    private const val CRLF = "\r\n"

    fun encode(value: RespValue): String = when (value) {
        is RespValue.SimpleString -> "+${value.value}$CRLF"
        is RespValue.Error -> "-${value.message}$CRLF"
        is RespValue.Integer -> ":${value.value}$CRLF"
        is RespValue.BulkString -> encodeBulkString(value.value)
        is RespValue.Array -> encodeArray(value.elements)
    }

    fun encodeToBytes(value: RespValue): ByteArray =
        encode(value).toByteArray(Charsets.UTF_8)

    private fun encodeBulkString(text: String?): String {
        if (text == null) {
            return "\$-1$CRLF"
        }
        // Redis RESP 규격에서 BulkString의 길이는 글자 수가 아니라 UTF-8 바이트 길이입니다.
        val byteLength = text.toByteArray(Charsets.UTF_8).size
        return "\$$byteLength$CRLF$text$CRLF"
    }

    private fun encodeArray(elements: List<RespValue>): String {
        val builder = StringBuilder("*${elements.size}$CRLF")
        for (element in elements) {
            builder.append(encode(element))
        }
        return builder.toString()
    }
}

