/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.service

import java.util.concurrent.ConcurrentLinkedQueue

object MetricsLog {
    private val maxPoints = 1440
    private val buffer = ConcurrentLinkedQueue<MutableMap<String, Any?>>()

    fun snapshot(tps: Int, load: Double, heapUsed: Long, heapMax: Long, cpuLoad: Double?) {
        val point = mutableMapOf<String, Any?>(
            "ts" to System.currentTimeMillis(),
            "tps" to tps,
            "load" to load,
            "heapUsed" to heapUsed,
            "heapMax" to heapMax,
            "cpuLoad" to cpuLoad
        )
        buffer.add(point)
        while (buffer.size > maxPoints) buffer.poll()
    }

    fun getHistory(): List<Map<String, Any?>> = buffer.toList()
}
