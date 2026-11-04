package com.miniredis.server.protocol

/**
 * RESP(REdis Serialization Protocol) 데이터 타입을 표현하는 모델.
 */
sealed interface RespValue {

    /**
     * 단순 문자열 (Prefix: '+')
     * 예: +OK\r\n, +PONG\r\n
     */
    data class SimpleString(val value: String) : RespValue

    /**
     * 에러 메시지 (Prefix: '-')
     * 예: -ERR unknown command\r\n
     */
    data class Error(val message: String) : RespValue

    /**
     * 64비트 부호 있는 정수 (Prefix: ':')
     * 예: :1000\r\n
     */
    data class Integer(val value: Long) : RespValue

    /**
     * 길이가 명시된 바이너리 안전 문자열 (Prefix: '$')
     * 예: $5\r\nhello\r\n (value가 null이면 Null Bulk String인 $-1\r\n을 의미)
     */
    data class BulkString(val value: String?) : RespValue

    /**
     * 여러 RespValue 요소들의 배열 (Prefix: '*')
     * 클라이언트가 서버로 명령어를 보낼 때 기본적으로 이 배열 형태를 사용합니다.
     * 예: *2\r\n$4\r\nECHO\r\n$5\r\nhello\r\n
     */
    data class Array(val elements: List<RespValue>) : RespValue {
        constructor(vararg elements: RespValue) : this(elements.toList())
    }

    /**
     * 명령어나 인자를 추출할 때 편하게 문자열 값을 꺼내기 위한 헬퍼 함수
     */
    fun asString(): String? = when (this) {
        is SimpleString -> value
        is BulkString -> value
        else -> null
    }

    companion object {
        val OK = SimpleString("OK")
        val PONG = SimpleString("PONG")
        val NULL = BulkString(null)
    }
}

