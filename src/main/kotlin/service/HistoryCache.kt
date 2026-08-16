/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.service

import java.util.concurrent.ConcurrentHashMap

object HistoryCache {
    data class Entry(val data: Any, val timestamp: Long = System.currentTimeMillis())
    private val cache = ConcurrentHashMap<String, Entry>()
    private val ttlMs = 10_000L // 10 seconds

    fun get(key: String): Any? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > ttlMs) {
            cache.remove(key)
            return null
        }
        return entry.data
    }

    fun put(key: String, data: Any) {
        cache[key] = Entry(data)
    }

    fun invalidate() { cache.clear() }
}
