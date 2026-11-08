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
            "SET" -> handleSet(args)
            "GET" -> handleGet(args)
            "DEL" -> handleDel(args)
            "EXISTS" -> handleExists(args)
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

    private fun handleSet(args: List<String>): RespValue {
        if (args.size != 2) {
            return RespValue.Error("ERR wrong number of arguments for 'set' command")
        }
        database.set(args[0], args[1])
        return RespValue.OK
    }

    private fun handleGet(args: List<String>): RespValue {
        if (args.size != 1) {
            return RespValue.Error("ERR wrong number of arguments for 'get' command")
        }
        val value = database.get(args[0])
        return RespValue.BulkString(value)
    }

    private fun handleDel(args: List<String>): RespValue {
        if (args.isEmpty()) {
            return RespValue.Error("ERR wrong number of arguments for 'del' command")
        }
        val deletedCount = database.del(args)
        return RespValue.Integer(deletedCount)
    }

    private fun handleExists(args: List<String>): RespValue {
        if (args.isEmpty()) {
            return RespValue.Error("ERR wrong number of arguments for 'exists' command")
        }
        val existingCount = database.exists(args)
        return RespValue.Integer(existingCount)
    }
}

