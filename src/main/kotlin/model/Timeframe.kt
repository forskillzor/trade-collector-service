/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.model

const val DAY_MS = 86_400_000L

enum class Timeframe(
    val code: String,
    val durationMs: Long,
    val source: Timeframe?,
    val sourceBarCount: Int,
    val retentionMs: Long?
) {
    M1("1m", 60_000L, null, 0, 30L * DAY_MS),
    M5("5m", 300_000L, M1, 5, 180L * DAY_MS),
    M15("15m", 900_000L, M1, 15, null),
    H1("1h", 3_600_000L, M15, 4, null),
    H4("4h", 14_400_000L, M15, 16, null),
    D1("1d", 86_400_000L, M15, 96, null);

    fun bucketStart(timestamp: Long): Long = timestamp / durationMs * durationMs

    fun bucketEnd(timestamp: Long): Long = bucketStart(timestamp) + durationMs

    fun isAligned(timestamp: Long): Boolean = timestamp % durationMs == 0L

    companion object {
        val ordered: List<Timeframe> = values().toList()
        val supportedCodes: List<String> = ordered.map { it.code }
        private val byCode: Map<String, Timeframe> = ordered.associateBy { it.code }

        fun fromCode(code: String): Timeframe? = byCode[code]

        fun parse(codes: Collection<String>): List<Timeframe> {
            val requested = codes.toSet()
            return ordered.filter { it.code in requested }
        }
    }
}
