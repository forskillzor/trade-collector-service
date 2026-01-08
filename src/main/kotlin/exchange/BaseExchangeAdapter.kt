package com.aandios.exchange

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

abstract class BaseExchangeAdapter(override val name: String) : ExchangeAdapter {
    protected val mapper = jacksonObjectMapper()

    override fun getSubscribeMessage(symbol: String): String? = null
}