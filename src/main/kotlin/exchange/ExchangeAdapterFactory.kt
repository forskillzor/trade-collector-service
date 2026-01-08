package com.aandios.exchange

import com.aandios.exchange.binance.BinanceAdapter
import com.aandios.exchange.bybit.BybitAdapter

object ExchangeAdapterFactory {
    fun createAdapter(exchangeName: String): ExchangeAdapter {
        return when (exchangeName.lowercase()) {
            "binance" -> BinanceAdapter()
            "bybit" -> BybitAdapter()
            else -> throw IllegalArgumentException("❌ Неизвестная биржа: $exchangeName")
        }
    }

    fun getSupportedExchanges(): List<String> = listOf("Binance", "Bybit")
}