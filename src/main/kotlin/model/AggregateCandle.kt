package com.aandios.model

import java.math.BigDecimal
import java.nio.ByteBuffer

data class AggregateCandle(
    val exchange: String,
    val symbol: String,
    val timeframe: String,
    val startTime: Long,
    val endTime: Long,

    // Arrow файл с данными
    val arrowData: ByteBuffer,

    // Метаданные
    val totalTicks: Long,
    val minPrice: BigDecimal,
    val maxPrice: BigDecimal,
    val priceLevels: Int
)

// Структура Arrow файла
data class PriceLevelData(
    val price: BigDecimal,
    var bidVolume: BigDecimal = BigDecimal.ZERO, // объём на bid
    var askVolume: BigDecimal = BigDecimal.ZERO, // объём на ask
    var bidCount: Int = 0,                       // количество bid сделок
    var askCount: Int = 0                        // количество ask сделок
)