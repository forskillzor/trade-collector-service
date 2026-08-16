/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.exchange.bybit

import com.aandios.exchange.BaseExchangeAdapter
import com.aandios.model.Trade
import java.math.BigDecimal

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
                        price = BigDecimal(tradeData["p"].asText()),
                        quantity = BigDecimal(tradeData["v"].asText()),
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