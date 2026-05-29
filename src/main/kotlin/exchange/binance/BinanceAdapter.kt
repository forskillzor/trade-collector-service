package com.aandios.exchange.binance

import com.aandios.exchange.BaseExchangeAdapter
import com.aandios.model.Trade

class BinanceAdapter : BaseExchangeAdapter("Binance") {
    override fun getWebSocketUrl(symbol: String): String {
        return "wss://fstream.binance.com/market/ws/${symbol.lowercase()}@aggTrade"
    }

    override fun parseTrade(json: String, symbol: String): Trade? {
        return try {
            val node = mapper.readTree(json)
            Trade(
                exchange = name,
                symbol = symbol.uppercase(),
                timestamp = node["T"].asLong(),
                price = node["p"].asDouble(),
                quantity = node["q"].asDouble(),
                isBuy = !node["m"].asBoolean()
            )
        } catch (e: Exception) {
            null
        }
    }

    override fun isTradeMessage(json: String): Boolean {
        return try {
            val node = mapper.readTree(json)
            node.has("e") && node["e"].asText() == "aggTrade"
        } catch (e: Exception) {
            false
        }
    }
}