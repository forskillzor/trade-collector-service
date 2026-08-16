/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.model

import java.math.BigDecimal

data class AggregateCandle(
    val exchange: String,
    val symbol: String,
    val timeframe: String,
    val startTime: Long,
    val endTime: Long,
    val priceLevelsJson: String,
    val totalTicks: Long,
    val minPrice: BigDecimal,
    val maxPrice: BigDecimal,
    val priceLevels: Int
)

data class PriceLevelData(
    val price: BigDecimal,
    var bidVolume: BigDecimal = BigDecimal.ZERO, // объём на bid
    var askVolume: BigDecimal = BigDecimal.ZERO, // объём на ask
    var bidCount: Int = 0,                       // количество bid сделок
    var askCount: Int = 0                        // количество ask сделок
)