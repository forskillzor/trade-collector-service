# TradeCollectorService — Аудит готовности к продакшену

**Дата:** 2026-05-30  
**Версия кода:** 2.0.0 (commit `85c94aa cleanup logs`)  
**Вывод:** Проект НЕ готов к продакшену. 0 блокирующих, 9 критических, 7 средних.

---

## Сводная таблица

| # | Проблема | Серьёзность | Файл | Строка |
|---|----------|------------|------|--------|
| 1 | ~~Пароль БД в открытом виде в репозитории~~ ✅ | RESOLVED | `config/production.json` | 15 |
| 2 | ~~IP сервера в исходном коде~~ ✅ | RESOLVED | `config/AppConfig.kt` | 21 |
| 3 | ~~Нет ни одного теста~~ → тесты есть, но неполные | 🟡 MEDIUM | `src/test/` | — |
| 4 | ~~`Double` для цен/объёмов — потеря точности~~ ✅ | RESOLVED | `model/Trade.kt` | 8-9 |
| 5 | ~~Data Race в `BatchProcessor.flushBatch()`~~ ✅ | RESOLVED | `service/BatchProcessor.kt` | 37, 86 |
| 6 | ~~Data Race в `VolumeFilterProcessor.SlidingWindowStats`~~ ✅ | RESOLVED | `service/VolumeFilterProcessor.kt` | 21, 29-37 |
| 7 | ~~Config.json загружается дважды при старте~~ ✅ | RESOLVED | `Main.kt` | 27, 37 |
| 8 | Resource leak: `ExchangeClient` создаёт `CoroutineScope` на символ | 🔴 CRITICAL | `service/ExchangeClient.kt` | 42 |
| 9 | Apache Arrow — утечка off-heap памяти | 🔴 CRITICAL | `service/AggregateProcessor.kt` | 94 |
| 10 | `filtered_trades` — одиночные INSERT вместо batch | 🔴 CRITICAL | `service/VolumeFilterProcessor.kt` | 215 |
| 11 | Data Race в `TradeProcessor.InstrumentStats` | 🔴 CRITICAL | `service/TradeProcessor.kt` | 33-37 |
| 12 | Нет Circuit Breaker для PostgreSQL | 🔴 CRITICAL | — | — |
| 13 | Graceful shutdown без таймаутов | 🔴 CRITICAL | `Main.kt` | 92-94 |
| 14 | `MonitoringServer` слушает только localhost | 🔴 CRITICAL | `service/MonitoringServer.kt` | 34 |
| 15 | HikariCP без leak-detection и keepalive | 🔴 CRITICAL | `storage/postgres/TradeDAO.kt` | 21-37 |
| 16 | t-digest добавлен, но не используется | 🟡 MEDIUM | `service/VolumeFilterProcessor.kt` | 93 |
| 17 | Exposed ORM + kotlin-csv — неиспользуемые зависимости | 🟡 MEDIUM | `build.gradle.kts` | 66-69, 86 |
| 18 | Export-функциональность не реализована | 🟡 MEDIUM | — | — |
| 19 | `filtered_trades` PARTITION BY RANGE без реальных партиций | 🟡 MEDIUM | `sql/001_init_schema.sql` | 84 |
| 20 | `/health` не проверяет БД и WebSocket | 🟡 MEDIUM | `service/MonitoringServer.kt` | 55-63 |
| 21 | Нет DeadLetterQueue / DiskBuffer — потеря данных при сбоях | 🟡 MEDIUM | — | — |

---

## Детальный разбор

### ~~BLOCKER #1: Пароль БД в репозитории~~ ✅ RESOLVED

**Файл:** `config/production.json:15`
```json
"password": null
```

**Исправлено:** Пароль заменён на `null` в `config/production.json` и `config/config.example.json`. Боевой пароль передаётся только через `DB_PASSWORD` env var (через `/etc/default/trade-collector` на VPS). Механизм `resolvedPassword()` в `AppConfig.kt:42` уже готов.

**⚠️ Осталось:** сменить реальный пароль на VPS (он сохранился в git-истории старых коммитов).

---

### ~~BLOCKER #2: IP сервера в исходном коде~~ ✅ RESOLVED

**Файл:** `config/AppConfig.kt:21`
```kotlin
val host: String = "localhost",
```

**Исправлено:** Значение по умолчанию заменено на `localhost`. Реальный хост передаётся через `DB_HOST` env var. Механизм `resolvedHost()` (строка 30) читает `System.getenv("DB_HOST")`.

---

### ~~BLOCKER #3: Нет тестов~~ → MEDIUM: тесты есть, но неполные

Сейчас: **8 тестовых файлов, ~56 тестов**, `./gradlew test` — SUCCESS.

| Файл | Тестов | Что покрыто |
|------|--------|------------|
| `BinanceAdapterTest.kt` | 6 | parseTrade (happy-path, malformed, missing fields), isTradeMessage |
| `BybitAdapterTest.kt` | 9 | parseTrade (buy/sell, topic mismatch, empty data, missing fields), isTradeMessage, getWebSocketUrl |
| `BatchProcessorTest.kt` | 7 | enqueue, multi-instrument, batchSize flush, DAO failure re-queue, flush-on-stop, queue removal |
| `VolumeFilterProcessorTest.kt` | 7 | sliding window, window size limit, slideStep boundary, filtered trade threshold, getStats |
| `AggregateProcessorTest.kt` | 10 | calculateCandleStart/EndTime for all timeframes, epoch edge cases |
| `AggregateCandleBuilderTest.kt` | 9 | min/max price, bid/ask separation, JSON serialization, sorting, empty builder |
| `TradeTest.kt` | 5 | getVolumeUsd, fromRaw(BigDecimal), toLocalDateTime, isBuy |
| `FilteredTradeTest.kt` | 3 | TradeCategory enum, constructor with/without category |

**Осталось:**
- `recalculateWindowStats()` — не проверена числовая корректность перцентилей (p50/p95/p98/p99, stddev)
- `TradeDAO.insertRawTradesBatch()` — нет интеграционного теста с Testcontainers
- `BatchProcessor` — нет конкурентного теста (много продюсеров + таймер flush)

---

### ~~BLOCKER #4: `Double` для цен и объёмов~~ ✅ RESOLVED

**Файл:** `model/Trade.kt:12-13`, адаптеры, DAO, процессоры

**Исправлено:**
- `Trade.price`, `Trade.quantity` — `Double` → `BigDecimal`
- `Trade.getVolumeUsd()` — возвращает `BigDecimal` через `price.multiply(quantity)`
- `Trade.fromRaw()` — больше не конвертит в `.toDouble()`, сохраняет оригинальную точность
- Адаптеры (Binance, Bybit) — парсят JSON как `BigDecimal(node["p"].asText())` вместо `asDouble()`
- `TradeDAO` — убраны избыточные `BigDecimal.valueOf(trade.price)` (теперь передаётся напрямую)
- `AggregateProcessor`, `VolumeFilterProcessor`, `TradeProcessor` — убраны конвертации, логи форматируют BigDecimal напрямую
- Все тесты (TradeTest, AdapterTests, BatchProcessorTest, VolumeFilterProcessorTest, AggregateCandleBuilderTest, FilteredTradeTest) обновлены

---

### ~~BLOCKER #5: Data Race в `BatchProcessor`~~ ✅ RESOLVED

**Файл:** `service/BatchProcessor.kt:60`

**Исправлено:** `flushBatch()` обёрнут в `synchronized(tradeQueues) { ... }`. Все вызовы (из `addTrade()` и `processBatchLoop()`) теперь сериализованы — исключена гонка на `poll()`/`remove()`.

---

### ~~BLOCKER #6: Data Race в `VolumeFilterProcessor`~~ ✅ RESOLVED

**Файл:** `service/VolumeFilterProcessor.kt:22, 36`

**Исправлено:** Добавлен per-instrument lock через `ConcurrentHashMap<String, Any>`. Весь `processTrade()` обёрнут в `synchronized(lock) { ... }`. Гонка на `var`-полях и изменяемых коллекциях внутри `SlidingWindowStats` устранена.

---

### ~~CRITICAL #7: Config загружается дважды~~ ✅ RESOLVED

**Файл:** `Main.kt:27`

**Исправлено:** Убран дублирующий вызов `ConfigManager.loadFromFile(configPath)` (строка 37). Остался только один вызов с поиском по 6 путям, результат проверяется, после чего сразу `getConfig()`.

---

### 🔴 CRITICAL #8: Resource leak в `ExchangeClient`

**Файл:** `service/ExchangeClient.kt:42`
```kotlin
val job = CoroutineScope(Dispatchers.IO).launch {
    connectAndListen(url, symbol, client)
}
```

Для каждого symbol создаётся новый `CoroutineScope(Dispatchers.IO)`. При `stop()` вызывается `clientJobs.forEach { it.cancel() }`, но сам Scope не отменяется — висит в памяти. При 10 символах = 10 утекших scope'ов.

**Исправление:** Использовать общий scope из `TradeCollectorService` (он уже передаётся через `launchClientForSymbol`), либо хранить scope и отменять его.

---

### 🔴 CRITICAL #9: Apache Arrow — утечка off-heap памяти

**Файл:** `service/AggregateProcessor.kt:16, 94`
```kotlin
private val allocator = RootAllocator()
// ...
val childAllocator = this@AggregateProcessor.allocator.newChildAllocator("candle-builder", 0, Long.MAX_VALUE)
```

`RootAllocator` без лимита памяти. Каждый вызов `buildArrowData()` создаёт `childAllocator`, который в `finally` закрывается, но native-память обратно ОС не возвращается (только в пул). При длительной работе off-heap память растёт.

Плюс: `Float8Vector` конвертирует `BigDecimal.toDouble()` — теряется точность (связано с BLOCKER #4).

**Исправление:** Заменить Arrow на JSONB в PostgreSQL (уже запланировано в `critical-issues.md`).

---

### 🔴 CRITICAL #10: Одиночные INSERT для filtered_trades

**Файл:** `service/VolumeFilterProcessor.kt:203`
```kotlin
dao.insertFilteredTrade(filteredTrade)  // одиночная вставка!
```

Метод `insertFilteredTradesBatch()` уже реализован в DAO (строка 180), но не используется. Каждая китовая сделка — отдельный SQL-запрос.

**Исправление:** Аккумулировать `FilteredTrade` в буфер и вставлять batch'ами через `insertFilteredTradesBatch()`.

---

### 🔴 CRITICAL #11: Data Race в `TradeProcessor.InstrumentStats`

**Файл:** `service/TradeProcessor.kt:33-37`
```kotlin
data class InstrumentStats(
    var totalTrades: Long = 0,       // var Long — не атомарно
    var lastTradeTime: Long = 0,
    var batchQueueSize: Int = 0
)
private val instrumentStats = ConcurrentHashMap<String, InstrumentStats>()
```

`totalTrades++` (строка 47) не атомарно для `Long`. Несколько корутин могут одновременно инкрементировать счётчик.

**Исправление:** `AtomicLong` / `AtomicInteger` вместо `var`.

---

### 🔴 CRITICAL #12: Нет Circuit Breaker для PostgreSQL

При падении PostgreSQL сервис продолжает бесконечно ретраить вставки. `BatchProcessor.flushBatch()` при ошибке кладёт батч обратно в очередь (строка 74-77) — бесконечный цикл. Это ведёт к:
- Росту очереди до OOM
- Деградации всех остальных компонентов

**Исправление:** Circuit Breaker с состояниями CLOSED → OPEN → HALF_OPEN (описан в `plans/critical-issues.md`).

---

### 🔴 CRITICAL #13: Graceful shutdown без таймаутов

**Файл:** `Main.kt:92-94`
```kotlin
val shutdownHook = Thread {
    runBlocking {
        service.stop()
        dao.shutdown()
    }
}
```

`service.stop()` → `tradeProcessor.shutdown()` → `aggregateProcessor.flushAll()` — синхронная операция без таймаута. При большом количестве незакрытых свечей может занять минуты. systemd убьёт процесс через 90 секунд, не дождавшись.

**Исправление:** `withTimeout(30.seconds)` на каждую стадию shutdown.

---

### 🔴 CRITICAL #14: MonitoringServer только на localhost

**Файл:** `service/MonitoringServer.kt:34`
```kotlin
val engine = embeddedServer(Jetty, port = port, host = "localhost") {
```

В Docker/K8s healthcheck и Prometheus не достучатся.

**Исправление:** Сделать host конфигурируемым (`0.0.0.0` для контейнеров).

---

### 🔴 CRITICAL #15: HikariCP без мониторинга утечек

**Файл:** `storage/postgres/TradeDAO.kt:21-37`

Не настроены:
- `leakDetectionThreshold` — не обнаружит утечку соединений
- `keepaliveTime` — соединения могут умирать при простое
- `connectionTestQuery` / `validationTimeout` — не проверит живость перед выдачей

**Исправление:**
```kotlin
leakDetectionThreshold = 2000
keepaliveTime = 300000
connectionTestQuery = "SELECT 1"
```

---

### 🟡 MEDIUM #16: t-digest не используется

Библиотека `com.tdunning:t-digest:3.3` добавлена (build.gradle.kts:89), но `VolumeFilterProcessor` до сих пор сортирует весь список для перцентилей — O(n log n) каждый `slideStep`. t-digest дал бы O(log n) и константную память.

---

### 🟡 MEDIUM #17: Неиспользуемые зависимости

- `org.jetbrains.exposed:*` (4 модуля, строки 66-69) — весь доступ к БД через raw JDBC
- `com.github.doyaaaaaken:kotlin-csv-jvm:1.9.2` (строка 86)

**Исправление:** Удалить из `build.gradle.kts`.

---

### 🟡 MEDIUM #18: Export не реализован

`ExportConfig` описан в конфигурации, но код экспорта отсутствует. Либо реализовать, либо убрать.

---

### 🟡 MEDIUM #19: Партиционирование без партиций

**Файл:** `sql/001_init_schema.sql:84`
```sql
) PARTITION BY RANGE (timestamp);
```

Таблица `filtered_trades` объявлена партиционированной, но партиции не созданы. Данные попадают в дефолтную партицию — преимуществ нет.

---

### 🟡 MEDIUM #20: `/health` не проверяет зависимости

**Файл:** `service/MonitoringServer.kt:55-63`

Всегда возвращает `"healthy"`, не проверяя PostgreSQL и WebSocket-соединения. Непригоден для Kubernetes readiness probe.

---

### 🟡 MEDIUM #21: Нет DeadLetterQueue и DiskBuffer

Эти компоненты описаны в планах (`critical-issues.md`), но не реализованы. При сбое БД данные теряются (нет персистентного буфера). «Плохие» сообщения не изолируются.

---

## Что ИЗМЕНИЛОСЬ после rollback'а

По сравнению с предыдущей версией кода (до rollback'а):

| Компонент | Было | Стало |
|-----------|------|-------|
| DeadLetterQueue | Был класс | **Отсутствует** |
| DiskBuffer | Был класс | **Отсутствует** |
| Структура адаптеров | `adapters/` | `exchange/binance/`, `exchange/bybit/` |
| Trade.kt | Только `Double` | Добавлен `fromRaw(BigDecimal)` (но всё равно конвертит в Double) |
| BatchProcessor | `MutableMap` | `ConcurrentHashMap` + `ConcurrentLinkedQueue` (лучше, но гонка осталась) |
| MonitoringServer | 4 эндпоинта | Добавлены `/exchanges`, `/database/stats` |
| Закомментированный код | Нет | Много закомментированных полей в `/status` |

**Итог:** rollback откатил на более раннюю версию — меньше реализованных компонентов, больше заглушек.

---

## Приоритетный план исправлений

### Фаза 1 — Блокирующие (неделя 1)
| # | Задача | Оценка |
|---|--------|--------|
| ~~1.1~~ | ~~Удалить пароль из `production.json`, сменить пароль на сервере~~ ✅ | — |
| ~~1.2~~ | ~~Убрать IP из `AppConfig.kt`~~ ✅ | — |
| ~~1.3~~ | ~~Заменить `Double` на `BigDecimal` в `Trade` и всех расчётах~~ ✅ | — |
| ~~1.4~~ | ~~Исправить data race в `BatchProcessor` (synchronized на flushBatch)~~ ✅ | — |
| ~~1.5~~ | ~~Исправить data race в `VolumeFilterProcessor` (Mutex на окно)~~ ✅ (synchronized per-instrument) | — |
| ~~1.3~~ | ~~Написать минимальный набор тестов~~ → есть 56 тестов, остались пробелы (см. выше) | — |

### Фаза 2 — Критические (неделя 2)
| # | Задача | Оценка |
|---|--------|--------|
| 2.1 | Убрать двойную загрузку конфига, путь `config/production.json` | 15 мин |
| 2.2 | Исправить resource leak в `ExchangeClient` | 1 ч |
| 2.3 | Заменить Arrow на JSONB в `AggregateProcessor` | 8 ч |
| 2.4 | Batch-вставка `filtered_trades` | 2 ч |
| 2.5 | Atomic-поля в `InstrumentStats` | 30 мин |
| 2.6 | Circuit Breaker для БД | 4 ч |
| 2.7 | Таймауты в graceful shutdown | 1 ч |
| 2.8 | `0.0.0.0` для MonitoringServer | 15 мин |
| 2.9 | HikariCP: leakDetection + keepalive + validation | 15 мин |

### Фаза 3 — Средние (неделя 3)
| # | Задача | Оценка |
|---|--------|--------|
| 3.1 | Интегрировать t-digest в VolumeFilterProcessor | 4 ч |
| 3.2 | Удалить неиспользуемые зависимости | 15 мин |
| 3.3 | Реализовать DeadLetterQueue | 4 ч |
| 3.4 | Реализовать DiskBuffer | 4 ч |
| 3.5 | Создать партиции для `filtered_trades` | 1 ч |
| 3.6 | `/health` с проверкой БД и WebSocket | 1 ч |

---

## Итог

| Категория | Количество |
|-----------|-----------|
| 🔴 BLOCKER | 0 |
| 🔴 CRITICAL | 9 |
| 🟡 MEDIUM | 7 |
| **Всего** | **16** |

**Оценка трудозатрат:** ~50 человеко-часов (2 недели для одного разработчика).

**Текущий статус после rollback'а:** Проект стал более сырым — отсутствуют DeadLetterQueue и DiskBuffer, больше закомментированного кода. Основные блокеры (пароль, IP, Double, data races) не изменились.
