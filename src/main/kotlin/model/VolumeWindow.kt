/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.model

import java.math.BigDecimal

data class VolumeWindow(
    val exchange: String,
    val symbol: String,
    val startTime: Long,
    val endTime: Long,
    val totalTrades: Int,

    // Статистика по объёмам
    val minVolume: BigDecimal,
    val maxVolume: BigDecimal,
    val avgVolume: BigDecimal,
    val medianVolume: BigDecimal,
    val stddevVolume: BigDecimal,

    // Перцентили
    val p50Volume: BigDecimal,
    val p95Volume: BigDecimal,
    val p98Volume: BigDecimal,
    val p99Volume: BigDecimal,

    // Порог фильтрации
    val filterPercentile: Double,
    val filterThreshold: BigDecimal
)