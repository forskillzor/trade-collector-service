package com.aandios.exchange.bybit

import com.aandios.exchange.BaseExchangeAdapter
import com.aandios.model.Trade

class BybitAdapter : BaseExchangeAdapter("Bybit") {
    override fun getWebSocketUrl(symbol: String): String {
        return "wss://stream.bybit.com/v5/public/spot"
    }

    override fun getSubscribeMessage(symbol: String): String {
        return """
            {
                "op": "subscribe",
                "args": ["publicTrade.$symbol"]
            }
        """.trimIndent()
    }

    override fun parseTrade(json: String, symbol: String): Trade? {
        return try {
            val node = mapper.readTree(json)

            if (node.has("topic") && node["topic"].asText() == "publicTrade.$symbol") {
                val data = node["data"]
                if (data.isArray && data.size() > 0) {
                    val tradeData = data[0]
                    return Trade(
                        exchange = name,
                        symbol = symbol.uppercase(),
                        timestamp = tradeData["T"].asLong(),
                        price = tradeData["p"].asDouble(),
                        quantity = tradeData["v"].asDouble(),
                        isBuy = tradeData["S"].asText() == "Buy"
                    )
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    override fun isTradeMessage(json: String): Boolean {
        return try {
            val node = mapper.readTree(json)
            node.has("topic") && node["topic"].asText().startsWith("publicTrade.")
        } catch (e: Exception) {
            false
        }
    }
}