package com.aandios.model

data class Trade(
    val exchange: String,    // Binance, Bybit
    val symbol: String,      // BTCUSDT, ETHUSDT
    val timestamp: Long,     // ts
    val price: Double,       // price
    val quantity: Double,    // qty/volume
    val direction: Boolean   // true = buy, false = sell
)