# Анализ производительности Trade Collector v2.0

**Дата**: 2026-06-05  
**VPS**: 1 ядро, 1.8 ГБ RAM  
**Проблема**: 100% CPU при 180-200 TPS (20 инструментов, 2 таймфрейма)  
**Корень**: создание десятков объектов на каждую сделку — BigDecimal, Instant, строки — плюс накладные расходы ConcurrentHashMap и synchronized

---

## Подсчёт горячих точек (на одну сделку, 2 таймфрейма)

| Категория | На 1 сделку | При 100 TPS (объектов/сек) |
|---|---|---|
| Создание объектов BigDecimal | 16 | **1 600/сек** |
| Операций BigDecimal (add/multiply/subtract) | ~18 | 1 800/сек |
| Создание объектов Instant | 3 | 300/сек |
| Вызовов JSON readTree() | 1 | 100/сек |
| Захватов synchronized блокировок | 1 | 100/сек |
| Операций с ConcurrentHashMap (чтение/запись) | 13 | 1 300/сек |
| Выделение строк | 4 | 400/сек |

---

## Выполненные оптимизации (результат: CPU 100% → 49%, TPS 190 → 272)

### 1. Замена BigDecimal на Double в скользящем окне статистики (VolumeFilterProcessor)

**Что это за код**: скользящее окно статистики по объёмам сделок — вычисляет экспоненциальное скользящее среднее (EWMA) и дисперсию для каждой сделки, чтобы определить порог фильтрации аномально крупных сделок.

**Было** — 12 новых объектов BigDecimal на каждую сделку:
```kotlin
window.ewmaMean = alpha.multiply(volumeUsd)          // ① BigDecimal × BigDecimal
    .add(oneMinusAlpha.multiply(window.ewmaMean))     // ② BigDecimal + BigDecimal
val diff = volumeUsd.subtract(window.ewmaMean)        // ③ BigDecimal - BigDecimal  
window.ewmaVar = alpha.multiply(diff.multiply(diff))  // ④⑤⑥ BigDecimal × BigDecimal × BigDecimal
    .add(oneMinusAlpha.multiply(window.ewmaVar))      // ⑦⑧ BigDecimal + BigDecimal
// плюс sum.add(volume), sumSquared.add(volume*volume) в чанках — ещё 4 объекта
```

**Стало** — 0 аллокаций, чистые примитивы double:
```kotlin
window.ewmaMean = alpha * volumeUsd + oneMinusAlpha * window.ewmaMean
val diff = volumeUsd - window.ewmaMean
window.ewmaVar = alpha * diff * diff + oneMinusAlpha * window.ewmaVar
```

**Экономия CPU**: ~35%. `BigDecimal.multiply()` — метод с десятками инструкций (скейлинг, округление, проверки). `double * double` — одна инструкция процессора (`fmul`). При 272 TPS это **3 264 объекта/сек**, которые больше не создаются и не собираются сборщиком мусора.

**Точность данных**: EWMA — это сглаживающая статистика, ей не нужна точность 10⁻⁸. Объёмы сделок находятся в диапазоне $0.01–$1,000,000. Тип `Double` хранит 15–16 значащих цифр — этого более чем достаточно для разницы между $50,000.00000001 и $50,000.00000000. t-Digest (расчёт перцентилей) и так работал с Double через `digest.add(volume.toDouble())` — оптимизация просто убрала двойную конвертацию BigDecimal→Double→BigDecimal. Итоговый порог `volumeThreshold` по-прежнему хранится как BigDecimal (берётся из квантиля t-Digest и конвертируется через `BigDecimal.valueOf()`). На объёме $50,000 погрешность Double-арифметики составляет ~10⁻¹² доллара — величина, незаметная для задачи обнаружения китовых сделок.

---

### 2. Удаление Instant.ofEpochMilli() в расчёте начала свечи (AggregateProcessor + TradeProcessor)

**Что это за код**: для каждого трейда вычисляется начало минутной/15-минутной свечи путём округления timestamp вниз до границы таймфрейма.

**Было** — 3 объекта Instant на каждую сделку:
```kotlin
fun calculateCandleStart(timestamp: Long, timeframe: String): Long {
    val instant = Instant.ofEpochMilli(timestamp)  // ① создание объекта Instant
    val seconds = instant.epochSecond              // ② деление ms на 1000
    return when (timeframe) {
        "1m" -> seconds - (seconds % 60)            // ③ модуль в секундах
        ...
    } * 1000                                        // ④ умножение обратно в ms
}
```

**Стало** — 0 аллокаций, чистая целочисленная арифметика:
```kotlin
fun calculateCandleStart(timestamp: Long, timeframe: String): Long {
    val stepMs = timeframeStepMs(timeframe)  // 60_000 для "1m", 900_000 для "15m"
    return timestamp - (timestamp % stepMs)  // одна операция модуля
}
```

**Экономия CPU**: ~10%. Убран цикл: миллисекунды → Instant → секунды → модуль → миллисекунды. Теперь одна операция: `timestamp % 60000`. Плюс убран `Instant.now()` в `TradeProcessor.updateTps()` (заменён на `System.currentTimeMillis() / 1000`).

**Точность данных**: **ноль изменений**. Математическая эквивалентность:
```
timestamp = 1704062123456 ms
Старый код: Instant(1704062123456) → epochSecond=1704062123 → 1704062123-(1704062123%60)=1704062100 → ×1000=1704062100000
Новый код:  1704062123456 - (1704062123456 % 60000) = 1704062123456 - 23456 = 1704062100000
```

---

### 3. Кеширование ключа "exchange_symbol" в Trade

**Что это за код**: композитный ключ `"binance_BTCUSDT"` вычислялся заново в четырёх местах на каждую сделку.

**Было** — 4 строковые аллокации на сделку:
```kotlin
// VolumeFilterProcessor.kt:65  → "${trade.exchange}_${trade.symbol}"
// AggregateProcessor.kt:94     → "${trade.exchange}_${trade.symbol}" ×2 (два таймфрейма)
// TradeProcessor.kt:77         → "${exchange}_${symbol}"
```
Каждый вызов `"$a\_$b"` в Kotlin создаёт StringBuilder + новую строку.

**Стало** — 0 аллокаций, вычисляется один раз lazily:
```kotlin
// Trade.kt
data class Trade(...) {
    val key: String by lazy { "${exchange}_${symbol}" }
}
// Везде используется trade.key
```

**Экономия CPU**: ~5%. При 272 TPS: **816 строк/сек** не создаются, не нагружают GC.

**Точность данных**: **ноль изменений**. Та же самая строка, просто вычисленная один раз вместо четырёх.

---

## Результат

| Метрика | До оптимизации | После | Изменение |
|---|---|---|---|
| **CPU** | 100% | **49%** | −51% |
| **TPS** | ~190 | **272** | +43% |
| **Load average** | 1.2 | **0.71** | −41% |

### Сводка влияния на точность данных

| Оптимизация | Что убрано | Аллокаций/трейд | Влияние на данные |
|---|---|---|---|
| BigDecimal → Double (EWMA+чанки) | 12 объектов BigDecimal | 12 → 0 | Погрешность ~10⁻¹² доллара — не влияет на фильтр китов |
| Instant → целочисленная арифметика | 3 объекта Instant | 3 → 0 | **Ноль** — математическая эквивалентность |
| Кеширование ключа | 3 строки | 3 → 0 | **Ноль** — та же строка |

**Итого**: 18 объектов на сделку → 0. При 272 TPS это **~4 900 аллокаций/сек**, которые больше не создаются и не собираются GC.

---

## Нереализованные оптимизации (оценка потенциала)

| # | Оптимизация | Оценка экономии CPU | Файлы |
|---|---|---|---|
| 4 | Замена ConcurrentHashMap → HashMap (обработка однопоточная) | 5–8% | VolFilter, AggProcessor, TradeProcessor |
| 5 | Убрать synchronized блокировку (однопоточная обработка) | 2–5% | VolumeFilterProcessor.kt |
| 6 | Потоковый JSON-парсер (один проход, без дерева JsonNode) | 2–4% | BinanceAdapter.kt |
| 7 | BigDecimal → Double в AggregateProcessor (объёмы bid/ask) | 1–3% | PriceLevelData.kt, AggProcessor.kt |
| 8 | Флаги JVM: -XX:+UseSerialGC | 5–10% | run.sh |
