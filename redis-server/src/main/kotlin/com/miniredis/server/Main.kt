package com.miniredis.server

fun main() {
    val server = MiniRedisServer()

    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop()
        }
    )

    server.start()
}

