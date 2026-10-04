package com.aandios.service

import com.aandios.model.LiquidationOrder
import mu.KotlinLogging

private val log = KotlinLogging.logger {}

class LiquidationProcessor(
    private val buffer: MinuteBuffer,
    private val enabled: Boolean = true
) {
    private var totalCount = 0L
    
    fun process(order: LiquidationOrder) {
        if (!enabled) return
        buffer.addLiquidation(order.symbol, order)
        totalCount++
        if (totalCount % 1000 == 0L) {
            log.debug { "Liquidations processed: $totalCount" }
        }
    }
    
    fun getTotalCount(): Long = totalCount
}
