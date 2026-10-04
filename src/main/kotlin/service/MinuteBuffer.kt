package com.aandios.service

import com.aandios.model.LiquidationOrder
import java.util.concurrent.ConcurrentHashMap

class MinuteBuffer {
    private val liquidationBuffer = ConcurrentHashMap<String, MutableList<LiquidationOrder>>()
    
    fun addLiquidation(symbol: String, liq: LiquidationOrder) {
        val s = symbol.uppercase()
        liquidationBuffer.getOrPut(s) { mutableListOf() }.let {
            synchronized(it) { it.add(liq) }
        }
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
}
