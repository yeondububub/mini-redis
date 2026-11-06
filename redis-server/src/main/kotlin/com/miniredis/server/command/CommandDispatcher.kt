package com.miniredis.server.command

import com.miniredis.server.protocol.RespValue
import com.miniredis.server.storage.RedisDatabase

/**
 * 클라이언트가 보낸 RESP 명령어 요청을 해석하고 실행하여 응답([RespValue])을 반환하는 디스패처.
 */
class CommandDispatcher(
    private val database: RedisDatabase = RedisDatabase()
) {

    fun dispatch(request: RespValue): RespValue {
        if (request !is RespValue.Array || request.elements.isEmpty()) {
            return RespValue.Error("ERR invalid command format")
        }

        val command = request.elements[0].asString()?.uppercase()
            ?: return RespValue.Error("ERR invalid command name")
        val args = request.elements.drop(1).map { it.asString() ?: "" }

        return when (command) {
            "PING" -> handlePing(args)
            "ECHO" -> handleEcho(args)
            "COMMAND" -> RespValue.Array(emptyList())
            else -> RespValue.Error("ERR unknown command '$command'")
        }
    }

    private fun handlePing(args: List<String>): RespValue = when (args.size) {
        0 -> RespValue.PONG
        1 -> RespValue.BulkString(args[0])
        else -> RespValue.Error("ERR wrong number of arguments for 'ping' command")
    }

    private fun handleEcho(args: List<String>): RespValue = when (args.size) {
        1 -> RespValue.BulkString(args[0])
        else -> RespValue.Error("ERR wrong number of arguments for 'echo' command")
    }
}

