# План: Batch-архитектура (обработка по расписанию вместо per-tick)

**Дата**: 2026-06-06  
**Статус**: на ревью

---

## Текущая архитектура (per-tick)

```
WebSocket → parse JSON → Trade
  ├─ batchProcessor.addTrade(trade)     — вставка в raw_trades (async)
  ├─ volumeFilter.processTrade(trade)   — EWMA, t-Digest, чанки — на каждый тик
  └─ aggregateProcessor.processTrade(trade) — накопление ценовых уровней — на каждый тик
```

**Проблемы**:
- Каждый тик: 18 объектов, BigDecimal-арифметика, ConcurrentHashMap, synchronized
- Даже после оптимизаций CPU = 49% (было 100%)
- Сложная многопоточная координация: 20 WebSocket-корутин + flush-корутины + watchdog

---

## Предлагаемая архитектура (batch)

```
WebSocket → parse JSON → Trade → batchProcessor.addTrade(trade) → raw_trades (только insert!)
                                   ↑
                                   только это в горячем цикле. Всё остальное — по расписанию.

Планировщик (один поток, Sequential):
  │
  ├─ каждую минуту (по tick-таймеру, а не wall-clock):
  │   для каждого symbol:
  │     прочитать raw_trades_{symbol} за прошедшую минуту
  │     построить 1m агрегат → INSERT в aggregates_{symbol}
  │     обновить volumeFilter: пересчитать EWMA + t-Digest из окна в 10K сделок
  │     если обнаружены китовые сделки → INSERT в filtered_trades_{symbol}
  │
  ├─ каждые 15 минут (кратно 15m границе):
  │   для каждого symbol:
  │     прочитать 15 готовых 1m агрегатов (или сырые трейды за 15 минут)
  │     смержить в 15m агрегат → INSERT в aggregates_{symbol}
  │
  └─ каждые N сделок или минут:
        очистить старые raw_trades (оставить 10K на символ)
```

---

## Детальный дизайн

### 1. Горячий цикл WebSocket (максимально лёгкий)

```kotlin
// ExchangeClient — без изменений, принимает фреймы
// TradeProcessor.process() — УПРОЩАЕТСЯ:
fun process(json: String, exchange: String, symbol: String) {
    val trade = adapter.parseTrade(json, symbol) ?: return
    totalTicks++
    batchProcessor.addTrade(trade)  // только очередь на вставку в raw_trades
    // ВСЁ. Никакого volumeFilter, никакого aggregateProcessor.
}
```

**Что уходит из горячего цикла**:
- `VolumeFilterProcessor.processTrade()` — вся EWMA/t-Digest/чанки
- `AggregateProcessor.processTrade()` — все расчёты свечей
- `updateTps()` — можно оставить (лёгкий: `System.currentTimeMillis() / 1000`)
- `instrumentStats` — можно оставить (лёгкий: AtomicLong инкремент)

### 2. BatchScheduler (новый компонент)

```kotlin
class BatchScheduler(
    private val dao: TradeDAO,
    private val symbols: List<String>,     // из конфига
    private val timeframes: List<String>   // ["1m", "15m"]
) {
    // Хранит lastProcessedTimestamp для каждого symbol/timeframe
    // Чтобы не обрабатывать одни и те же данные дважды
    private val watermarks = ConcurrentHashMap<String, Long>()
    
    // Запускается при старте сервиса, работает в своём CoroutineScope
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(1000) // проверка каждую секунду
                checkAndProcessMinuteBoundaries()
            }
        }
    }
}
```

**Алгоритм `checkAndProcessMinuteBoundaries()`**:

```
текущее_время_минута = System.currentTimeMillis() / 60000 * 60000  // округлённое до минуты

для каждого symbol:
  последняя_обработанная = watermarks[symbol] ?: (текущее_время_минута - 60000)
  
  пока последняя_обработанная < текущее_время_минута:
    начало = последняя_обработанная
    конец = начало + 60000
    
    // 1. Построить 1m агрегат
    трейды = dao.getRawTradesInRange(symbol, начало, конец)
    агрегат = buildAggregate(трейды, "1m")
    dao.saveAggregate(агрегат)
    
    // 2. Пересчитать volumeFilter статистику
    последние_10k = dao.getRecentRawTrades(symbol, 10000)
    статистика = calculateVolumeStats(последние_10k)
    dao.saveVolumeWindow(статистика)
    
    // 3. Проверить китовые сделки
    for трейд in трейды:
        if трейд.volumeUsd >= статистика.volumeThreshold:
            dao.saveFilteredTrade(трейд)
    
    последняя_обработанная = конец
  
  watermarks[symbol] = последняя_обработанная
```

**Для 15m свечи**:
- На границе 15m (каждые 15 минут) — смержить 15 готовых 1m агрегатов
- Или: прочитать сырые трейды за 15 минут и построить агрегат за один проход
- Первый вариант эффективнее: 15 SELECT по aggregates вместо одного большого по raw_trades

### 3. Контрольные точки (watermarks)

Нужны чтобы при рестарте сервиса не пропустить минуты и не обработать дважды:

```kotlin
// В БД или в памяти? При рестарте — потеряем.
// Решение: хранить в поле volume_windows.last_processed_timestamp или в отдельной таблице
// Проще: вычислять из MAX(end_time) в aggregates на старте
val lastProcessed = dao.getMaxAggregateEndTime(symbol, timeframe) ?: (System.currentTimeMillis() - 60000)
```

### 4. Гарантии доставки

| Событие | Что происходит |
|---|---|
| Сервис упал между 14:01 и 14:02 | raw_trades_btcusdt содержит трейды за 14:01. При рестарте `watermark[symbol]` вычисляется из MAX(end_time) → 14:01. BatchScheduler находит незавершённую минуту и достраивает агрегат |
| Две копии сервиса (случайно) | Не страшно: ON CONFLICT DO UPDATE в INSERT'ах |
| Сервис завис на 5 минут | При восстановлении BatchScheduler догоняет все пропущенные минуты подряд |
| Чистка raw_trades удалила нужные данные | BatchScheduler всегда обрабатывает данные ДО очистки. Очистка запускается только после того как минута обработана |

### 5. Удаление старых raw_trades

Сейчас очистка в `VolumeFilterProcessor.processTrade()` каждые 1000 сделок. В новой схеме:

```kotlin
// После обработки минутного батча:
if (totalRawTrades(symbol) > 10_000) {
    dao.cleanupOldRawTrades(symbol, 10_000)
}
```

---

## Что удаляется

| Компонент | Судьба |
|---|---|
| `VolumeFilterProcessor` | Полностью заменяется на `calculateVolumeStats(trades)` — функция без состояния, вызывается в батче |
| `AggregateProcessor` | Полностью заменяется на `buildAggregate(trades, timeframe)` — функция без состояния |
| `SlidingWindowStats`, `Chunk` | Удаляются — не нужны (нет in-memory окна) |
| `SlidingWindows`, `processedTrades`, `windowLocks` | Удаляются — все ConcurrentHashMap больше не нужны |
| `synchronized(lock)` | Удаляется — нет многопоточного доступа |
| `initFromDbIfNeeded` | Удаляется — батч-обработка всегда читает из БД |
| `flushAll` | Удаляется — нет in-memory состояния |
| `calculateCandleStart` (Instant) | Уже оптимизирован, остаётся |

---

## Оценка влияния

| Метрика | До (per-tick с оптимизациями) | После (batch) | Почему |
|---|---|---|---|
| **CPU** | 49% | **10-20%** | Горячий цикл: только JSON-парсинг + очередь. Вся арифметика — раз в минуту, а не 272 раза/сек |
| **TPS** | 272 | **400+** | WebSocket-цикл не блокируется вычислениями |
| **Память** | 512 MB heap | **~256 MB** | Нет in-memory окон (10K×20 symbols), нет ConcurrentHashMap |
| **Задержка агрегатов** | ~0 (per-tick) | **≤ 1 минута** | Агрегат строится по окончании минуты |
| **Задержка китов** | ~0 (per-tick) | **≤ 1 минута** | Фильтр пересчитывается по окончании минуты |
| **Сложность кода** | Высокая (stateful, многопоточная) | **Низкая** (stateless-функции, один поток) |

---

## План миграции (этапы)

### Этап 1: Создать stateless-функции
- `fun buildAggregate(trades: List<Trade>, timeframe: String): AggregateCandle`
- `fun calculateVolumeStats(trades: List<Trade>, percentile: Double): VolumeWindow`
- `fun detectFilteredTrades(trades: List<Trade>, threshold: BigDecimal): List<FilteredTrade>`

### Этап 2: Создать BatchScheduler
- Водяные знаки (watermarks) по symbol/timeframe
- Цикл проверки минутных границ
- Обработка 1m и 15m

### Этап 3: Упростить TradeProcessor
- Убрать вызовы volumeFilter и aggregateProcessor
- Оставить только batchProcessor.addTrade()

### Этап 4: Удалить старые компоненты
- VolumeFilterProcessor, AggregateProcessor
- SlidingWindowStats, Chunk, все ConcurrentHashMap

### Этап 5: Тестирование
- Запустить локально, проверить что агрегаты совпадают со старым кодом
- Запустить на VPS, сравнить CPU/TPS

---

## Риски

| Риск | Вероятность | Смягчение |
|---|---|---|
| BatchScheduler не успевает обработать 20 символов за минуту (следующая минута наступает до окончания обработки) | Средняя | Алгоритм догоняет: если отстал на N минут — обрабатывает их подряд. Главное — не допускать бесконечного отставания |
| ON CONFLICT DO UPDATE перезаписывает агрегат неполными данными | Низкая | Водяные знаки гарантируют что каждая минута обрабатывается ровно один раз |
| Задержка в 1 минуту неприемлема для дашборда | Низкая | Дашборд обновляется раз в 5 секунд. Задержка ≤60с непринципиальна для мониторинга |
