/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.service

import com.aandios.model.LiquidationOrder
import com.aandios.model.Trade
import java.util.concurrent.ConcurrentHashMap

class MinuteBuffer {
    private val liquidationBuffer = ConcurrentHashMap<String, MutableList<LiquidationOrder>>()
    private val tradeBuffer = ConcurrentHashMap<String, ConcurrentHashMap<Long, MutableList<Trade>>>()

    fun addLiquidation(symbol: String, liq: LiquidationOrder) {
        val s = symbol.uppercase()
        liquidationBuffer.computeIfAbsent(s) { mutableListOf<LiquidationOrder>() }.let {
            synchronized(it) { it.add(liq) }
        }
    }

    fun addTrade(trade: Trade) {
        val symbol = trade.symbol.uppercase()
        val minute = trade.timestamp / MINUTE_MS * MINUTE_MS
        val byMinute = tradeBuffer.computeIfAbsent(symbol) {
            ConcurrentHashMap<Long, MutableList<Trade>>()
        }
        val trades = byMinute.computeIfAbsent(minute) { mutableListOf<Trade>() }
        synchronized(trades) { trades.add(trade) }

        val cutoff = minute - MAX_BUFFERED_MINUTES * MINUTE_MS
        byMinute.keys.forEach { key ->
            if (key < cutoff) byMinute.remove(key)
        }
    }

    fun getTrades(symbol: String, start: Long, end: Long): List<Trade> {
        val byMinute = tradeBuffer[symbol.uppercase()] ?: return emptyList()
        val result = mutableListOf<Trade>()
        byMinute.forEach { (minute, trades) ->
            if (minute >= start && minute < end) {
                synchronized(trades) { result.addAll(trades) }
            }
        }
        return result
    }

    fun clearTrades(symbol: String, start: Long) {
        val key = symbol.uppercase()
        val byMinute = tradeBuffer[key] ?: return
        byMinute.remove(start)
        if (byMinute.isEmpty()) tradeBuffer.remove(key, byMinute)
    }
    
    data class LiquidationData(
        val liquidations: Map<String, List<LiquidationOrder>>
    )
    
    fun flush(): LiquidationData {
        val liquidations = mutableMapOf<String, List<LiquidationOrder>>()
        liquidationBuffer.forEach { (symbol, list) ->
            synchronized(list) {
                if (list.isNotEmpty()) {
                    liquidations[symbol] = list.toList()
                    list.clear()
                }
            }
        }
        return LiquidationData(liquidations)
    }
    
    fun totalLiquidationCount(): Int = liquidationBuffer.values.sumOf { synchronized(it) { it.size } }

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val MAX_BUFFERED_MINUTES = 2L
    }
}
