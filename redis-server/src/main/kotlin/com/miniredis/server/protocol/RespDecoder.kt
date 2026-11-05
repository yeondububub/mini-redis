package com.miniredis.server.protocol

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 클라이언트가 보낸 RESP 바이트 스트림을 읽어 [RespValue]로 역직렬화(파싱)하는 디코더.
 */
object RespDecoder {

    fun decode(text: String): RespValue? =
        decode(text.byteInputStream(Charsets.UTF_8))

    fun decode(input: InputStream): RespValue? {
        val line = readLine(input) ?: return null
        if (line.isEmpty()) return null

        val prefix = line[0]
        val payload = line.substring(1)

        return when (prefix) {
            '+' -> RespValue.SimpleString(payload)
            '-' -> RespValue.Error(payload)
            ':' -> RespValue.Integer(
                payload.toLongOrNull()
                    ?: throw IllegalArgumentException("잘못된 Integer 형식입니다: $line")
            )
            '$' -> decodeBulkString(payload, input)
            '*' -> decodeArray(payload, input)
            else -> decodeInlineCommand(line)
        }
    }

    private fun decodeBulkString(lengthPayload: String, input: InputStream): RespValue.BulkString {
        val byteLength = lengthPayload.toIntOrNull()
            ?: throw IllegalArgumentException("잘못된 BulkString 길이 형식입니다: $lengthPayload")

        if (byteLength < 0) {
            return RespValue.NULL
        }

        val buffer = ByteArray(byteLength)
        var totalRead = 0
        while (totalRead < byteLength) {
            val read = input.read(buffer, totalRead, byteLength - totalRead)
            if (read == -1) {
                throw IllegalArgumentException("BulkString 본문을 읽는 중 스트림이 예상치 못하게 종료되었습니다.")
            }
            totalRead += read
        }

        // 본문 뒤에 따라오는 끝맺음 \r\n 소비
        readLine(input)

        return RespValue.BulkString(String(buffer, Charsets.UTF_8))
    }

    private fun decodeArray(countPayload: String, input: InputStream): RespValue.Array {
        val count = countPayload.toIntOrNull()
            ?: throw IllegalArgumentException("잘못된 Array 개수 형식입니다: $countPayload")

        if (count <= 0) {
            return RespValue.Array(emptyList())
        }

        val elements = ArrayList<RespValue>(count)
        repeat(count) {
            val element = decode(input)
                ?: throw IllegalArgumentException("Array 요소를 읽는 중 스트림이 예상치 못하게 종료되었습니다.")
            elements.add(element)
        }
        return RespValue.Array(elements)
    }

    /**
     * nc(netcat) 등 터미널에서 직접 입력한 일반 텍스트 명령어(예: "PING", "SET key value")를
     * RESP 명령어 배열(Array of BulkString)로 변환합니다.
     */
    private fun decodeInlineCommand(line: String): RespValue.Array {
        val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return RespValue.Array(tokens.map { RespValue.BulkString(it) })
    }

    /**
     * 스트림에서 한 줄(\r\n 또는 \n 기준)을 읽어 UTF-8 문자열로 반환합니다.
     * 스트림이 끝났다면(EOF) null을 반환합니다.
     */
    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var hasReadAny = false

        while (true) {
            val byte = input.read()
            if (byte == -1) {
                return if (hasReadAny) buffer.toString(Charsets.UTF_8) else null
            }
            hasReadAny = true

            if (byte == '\n'.code) {
                break
            }
            buffer.write(byte)
        }

        val bytes = buffer.toByteArray()
        val length = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
            bytes.size - 1
        } else {
            bytes.size
        }
        return String(bytes, 0, length, Charsets.UTF_8)
    }
}

