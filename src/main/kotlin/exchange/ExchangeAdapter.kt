package com.aandios.exchange

import com.aandios.model.Trade

interface ExchangeAdapter {
    val name: String
    fun getWebSocketUrl(symbol: String): String
    fun getSubscribeMessage(symbol: String): String?
    fun parseTrade(json: String, symbol: String): Trade?
    fun isTradeMessage(json: String): Boolean

    fun supportsCombinedStream(): Boolean = false
    fun getCombinedStreamUrl(symbols: List<String>): String = ""
    fun parseCombinedFrame(json: String): Pair<String, String>? = null
}