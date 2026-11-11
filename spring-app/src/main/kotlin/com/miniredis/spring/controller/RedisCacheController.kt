package com.miniredis.spring.controller

import com.miniredis.spring.service.RedisCacheService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/redis")
class RedisCacheController(
    private val redisCacheService: RedisCacheService
) {

    @GetMapping("/ping")
    fun ping(): ResponseEntity<String> =
        ResponseEntity.ok(redisCacheService.ping() ?: "NO_RESPONSE")

    @PostMapping("/{key}")
    fun set(
        @PathVariable key: String,
        @RequestParam value: String
    ): ResponseEntity<String> {
        redisCacheService.set(key, value)
        return ResponseEntity.ok("OK")
    }

    @GetMapping("/{key}")
    fun get(@PathVariable key: String): ResponseEntity<String> {
        val value = redisCacheService.get(key)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(value)
    }

    @DeleteMapping("/{key}")
    fun delete(@PathVariable key: String): ResponseEntity<Boolean> =
        ResponseEntity.ok(redisCacheService.delete(key))
}

