package com.aandios.service

import com.aandios.model.PriceLevelData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.math.BigDecimal

private val log = KotlinLogging.logger {}

/**
 * (Де)сериализация списка ценовых уровней для price_levels_jsonb.
 *
 * ВАЖНО: PostgreSQL jsonb канонизирует JSON (пробелы после запятых:
 * `[[p, b, a, bc, ac], [...]]`), поэтому наивный split("],[") здесь
 * не работает — только полноценный JSON-парсинг (как в market-data-server).
 */
object PriceLevelsJson {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Парсит уровни из jsonb-текста вида `[[price, bidVolume, askVolume, bidCount, askCount], ...]`.
     * Пустой/битый JSON → пустой список (не падаем на старых данных).
     */
    fun parse(text: String?): List<PriceLevelData> {
        if (text.isNullOrBlank() || text == "[]") return emptyList()
        return try {
            json.parseToJsonElement(text).jsonArray.map { element ->
                val l = element.jsonArray
                PriceLevelData(
                    price = BigDecimal(l[0].jsonPrimitive.content),
                    bidVolume = BigDecimal(l[1].jsonPrimitive.content),
                    askVolume = BigDecimal(l[2].jsonPrimitive.content),
                    bidCount = l[3].jsonPrimitive.content.toInt(),
                    askCount = l[4].jsonPrimitive.content.toInt(),
                )
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to parse price_levels_json: ${text.take(120)}" }
            emptyList()
        }
    }

    /** Сериализует уровни в массив массивов (по возрастанию цены — порядок передаёт вызывающий). */
    fun build(levels: List<PriceLevelData>): String {
        if (levels.isEmpty()) return "[]"
        val sb = StringBuilder("[")
        levels.forEachIndexed { i, level ->
            if (i > 0) sb.append(',')
            sb.append("[")
            sb.append(level.price.toPlainString()).append(',')
            sb.append(level.bidVolume.toPlainString()).append(',')
            sb.append(level.askVolume.toPlainString()).append(',')
            sb.append(level.bidCount).append(',')
            sb.append(level.askCount)
            sb.append(']')
        }
        sb.append(']')
        return sb.toString()
    }
}
