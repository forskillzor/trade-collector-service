package com.aandios.model

import java.math.BigDecimal

data class LiquidationOrder(
    val exchange: String,
    val symbol: String,
    val timestamp: Long,
    val price: BigDecimal,
    val quantity: BigDecimal,
    val isLong: Boolean,          // SELL side = long liquidation being closed
    val orderType: String = ""    // "LIMIT" or "MARKET"
)
