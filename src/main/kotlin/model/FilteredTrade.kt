/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.model

import java.math.BigDecimal

data class FilteredTrade(
    val trade: Trade,
    val volumeUsd: BigDecimal,
    val percentileThreshold: Double, // например 0.98
    val volumeThreshold: BigDecimal, // пороговый объём
    val tradeCategory: TradeCategory? = null,
    val windowStartTime: Long,
    val windowEndTime: Long,
    val windowTotalTrades: Int
)

enum class TradeCategory {
    LARGE,      // > 98 перцентиль
    VERY_LARGE, // > 99 перцентиль
    WHALE       // > 99.5 перцентиль
}