package com.miniredis.server.command

import com.miniredis.server.protocol.RespValue
import com.miniredis.server.storage.RedisDatabase
import com.miniredis.server.storage.RedisWrongTypeException

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

        println("[MiniRedis] 명령어 수신: $command ${args.joinToString(" ")}".trim())

        return try {
            when (command) {
                "PING" -> handlePing(args)
                "ECHO" -> handleEcho(args)
                "SET" -> handleSet(args)
                "SETEX" -> handleSetEx(command, args, isMillis = false)
                "PSETEX" -> handleSetEx(command, args, isMillis = true)
                "GET" -> handleGet(args)
                "DEL" -> handleDel(args)
                "EXISTS" -> handleExists(args)
                "EXPIRE" -> handleExpire(command, args, isMillis = false)
                "PEXPIRE" -> handleExpire(command, args, isMillis = true)
                "TTL" -> handleTtl(command, args, isMillis = false)
                "PTTL" -> handleTtl(command, args, isMillis = true)
                "PERSIST" -> handlePersist(args)
                "INCR" -> handleIncrOrDecr(command, args, 1L)
                "DECR" -> handleIncrOrDecr(command, args, -1L)
                "INCRBY" -> handleIncrByOrDecrBy(command, args, 1L)
                "DECRBY" -> handleIncrByOrDecrBy(command, args, -1L)
                "HSET" -> handleHSet(args)
                "HGET" -> handleHGet(args)
                "HGETALL" -> handleHGetAll(args)
                "HDEL" -> handleHDel(args)
                "DBSIZE" -> RespValue.Integer(database.size().toLong())
                "FLUSHDB", "FLUSHALL" -> {
                    database.clear()
                    RespValue.OK
                }
                // --- Spring Boot (Lettuce) 및 클라이언트 초기 핸드셰이크 명령어 ---
                "HELLO" -> RespValue.Error("NOPROTO unsupported protocol version")
                "CLIENT" -> RespValue.OK
                "SELECT" -> RespValue.OK
                "INFO" -> RespValue.BulkString("# Server\r\nredis_version:7.0.0-mini-redis\r\n")
                "COMMAND" -> RespValue.Array(emptyList())
                else -> RespValue.Error("ERR unknown command '$command'")
            }
        } catch (e: RedisWrongTypeException) {
            RespValue.Error(e.message ?: "WRONGTYPE Operation against a key holding the wrong kind of value")
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
        return when (args.size) {
            2 -> {
                database.set(args[0], args[1])
                RespValue.OK
            }
            4 -> {
                val option = args[2].uppercase()
                val rawTtl = args[3].toLongOrNull()
                    ?: return RespValue.Error("ERR value is not an integer or out of range")
                if (rawTtl <= 0L) {
                    return RespValue.Error("ERR invalid expire time in 'set' command")
                }
                val ttlMillis = when (option) {
                    "EX" -> rawTtl * 1000L
                    "PX" -> rawTtl
                    else -> return RespValue.Error("ERR syntax error")
                }
                database.set(args[0], args[1], ttlMillis = ttlMillis)
                RespValue.OK
            }
            else -> RespValue.Error("ERR wrong number of arguments for 'set' command")
        }
    }

    private fun handleSetEx(command: String, args: List<String>, isMillis: Boolean): RespValue {
        if (args.size != 3) {
            return RespValue.Error("ERR wrong number of arguments for '${command.lowercase()}' command")
        }
        val rawTtl = args[1].toLongOrNull()
            ?: return RespValue.Error("ERR value is not an integer or out of range")
        if (rawTtl <= 0L) {
            return RespValue.Error("ERR invalid expire time in '${command.lowercase()}' command")
        }
        val ttlMillis = if (isMillis) rawTtl else rawTtl * 1000L
        database.set(args[0], args[2], ttlMillis = ttlMillis)
        return RespValue.OK
    }

    private fun handleExpire(command: String, args: List<String>, isMillis: Boolean): RespValue {
        if (args.size != 2) {
            return RespValue.Error("ERR wrong number of arguments for '${command.lowercase()}' command")
        }
        val rawTtl = args[1].toLongOrNull()
            ?: return RespValue.Error("ERR value is not an integer or out of range")
        val updated = if (isMillis) {
            database.pexpire(args[0], rawTtl)
        } else {
            database.expire(args[0], rawTtl)
        }
        return RespValue.Integer(if (updated) 1L else 0L)
    }

    private fun handleTtl(command: String, args: List<String>, isMillis: Boolean): RespValue {
        if (args.size != 1) {
            return RespValue.Error("ERR wrong number of arguments for '${command.lowercase()}' command")
        }
        val remaining = if (isMillis) database.pttl(args[0]) else database.ttl(args[0])
        return RespValue.Integer(remaining)
    }

    private fun handlePersist(args: List<String>): RespValue {
        if (args.size != 1) {
            return RespValue.Error("ERR wrong number of arguments for 'persist' command")
        }
        val persisted = database.persist(args[0])
        return RespValue.Integer(if (persisted) 1L else 0L)
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

    private fun handleIncrOrDecr(command: String, args: List<String>, delta: Long): RespValue {
        if (args.size != 1) {
            return RespValue.Error("ERR wrong number of arguments for '${command.lowercase()}' command")
        }
        return executeIncrBy(args[0], delta)
    }

    private fun handleIncrByOrDecrBy(command: String, args: List<String>, sign: Long): RespValue {
        if (args.size != 2) {
            return RespValue.Error("ERR wrong number of arguments for '${command.lowercase()}' command")
        }
        val amount = args[1].toLongOrNull()
            ?: return RespValue.Error("ERR value is not an integer or out of range")
        return executeIncrBy(args[0], amount * sign)
    }

    private fun executeIncrBy(key: String, delta: Long): RespValue =
        try {
            val result = database.incrBy(key, delta)
            RespValue.Integer(result)
        } catch (e: NumberFormatException) {
            RespValue.Error("ERR value is not an integer or out of range")
        } catch (e: ArithmeticException) {
            RespValue.Error("ERR increment or decrement would overflow")
        }

    private fun handleHSet(args: List<String>): RespValue {
        if (args.size < 3 || args.size % 2 == 0) {
            return RespValue.Error("ERR wrong number of arguments for 'hset' command")
        }
        val key = args[0]
        val pairs = ArrayList<Pair<String, String>>((args.size - 1) / 2)
        var index = 1
        while (index < args.size) {
            pairs.add(args[index] to args[index + 1])
            index += 2
        }
        val addedCount = database.hset(key, pairs)
        return RespValue.Integer(addedCount)
    }

    private fun handleHGet(args: List<String>): RespValue {
        if (args.size != 2) {
            return RespValue.Error("ERR wrong number of arguments for 'hget' command")
        }
        return RespValue.BulkString(database.hget(args[0], args[1]))
    }

    private fun handleHGetAll(args: List<String>): RespValue {
        if (args.size != 1) {
            return RespValue.Error("ERR wrong number of arguments for 'hgetall' command")
        }
        val flatEntries = database.hgetAll(args[0])
        return RespValue.Array(flatEntries.map { RespValue.BulkString(it) })
    }

    private fun handleHDel(args: List<String>): RespValue {
        if (args.size < 2) {
            return RespValue.Error("ERR wrong number of arguments for 'hdel' command")
        }
        val deletedCount = database.hdel(args[0], args.drop(1))
        return RespValue.Integer(deletedCount)
    }
}

