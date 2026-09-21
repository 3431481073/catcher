package com.example.clip.util

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object TimeUtil {
    // 与后端保持一致的 ISO8601 UTC（毫秒 + Z）
    private val formatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)

    fun nowIso(): String = formatter.format(Instant.now())

    fun nowPlusMillis(millis: Long): String = formatter.format(Instant.now().plusMillis(millis))

    fun nowMillis(): Long = Instant.now().toEpochMilli()

    fun parseMillis(iso: String): Long = Instant.parse(iso).toEpochMilli()
}
