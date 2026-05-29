# TradeCollectorService — Аудит готовности к продакшену

**Дата:** 2026-05-29  
**Версия кода:** 2.0.0 (commit `61e8d7f before fixing`)  
**Вывод:** Проект НЕ готов к продакшену. 6 блокирующих проблем, 9 критических, 6 средних.

---

## Сводная таблица

| # | Проблема | Серьёзность | Файл | Строка |
|---|----------|------------|------|--------|
| 1 | Пароль БД в открытом виде в репозитории | 🔴 BLOCKER | `config/production.json` | 20 |
| 2 | IP сервера в исходном коде | 🔴 BLOCKER | `config/AppConfig.kt` | 22 |
| 3 | Нет ни одного теста (unit/integration) | 🔴 BLOCKER | — | — |
| 4 | `Double` для цен/объёмов — потеря точности | 🔴 BLOCKER | `model/Trade.kt` | 8-9 |
| 5 | Data Race в `BatchProcessor.flushBatch()` | 🔴 BLOCKER | `service/BatchProcessor.kt` | 37, 86 |
| 6 | Data Race в `VolumeFilterProcessor.SlidingWindowStats` | 🔴 BLOCKER | `service/VolumeFilterProcessor.kt` | 21, 29-37 |
| 7 | Config.json загружается дважды при старте | 🔴 CRITICAL | `Main.kt` | 39, 50 |
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

### 🔴 BLOCKER #1: Пароль БД в репозитории

**Файл:** `config/production.json:20`
```json
"password": "JXDsdSZzXx1221!!!"
```

**Риск:** Любой с доступом к репозиторию видит боевой пароль PostgreSQL.

**Исправление:**
1. Немедленно сменить пароль на сервере
2. Удалить пароль из `config/production.json`
3. Передавать только через `DB_PASSWORD` env var (механизм `resolvedPassword` уже готов, строка 32)
4. Убедиться, что `config/production.json` в `.gitignore`

---

### 🔴 BLOCKER #2: IP сервера в исходном коде

**Файл:** `config/AppConfig.kt:22`
```kotlin
val host: String = "95.81.99.28",
```

**Риск:** Раскрытие инфраструктуры.

**Исправление:** Убрать значение по умолчанию. Оставить `localhost`, требовать `DB_HOST` env var.

---

### 🔴 BLOCKER #3: Нет тестов

`build.gradle.kts:95-97` объявляет `tasks.test { useJUnitPlatform() }`, но `src/test/` не существует. Ноль тестов.

**Минимальный набор:**
- `ExchangeAdapter.parseTrade()` — unit-тест парсинга JSON (Binance/Bybit)
- `VolumeFilterProcessor.recalculateWindowStats()` — unit-тест перцентилей
- `TradeDAO.insertRawTradesBatch()` — интеграционный тест с Testcontainers
- `BatchProcessor` — unit-тест конкурентного flush

---

### 🔴 BLOCKER #4: `Double` для цен и объёмов

**Файл:** `model/Trade.kt:8-9`
```kotlin
val price: Double,
val quantity: Double,
```

И метод `getVolumeUsd(): Double = price * quantity` (строка 17).

`fromRaw()` принимает `BigDecimal`, но тут же конвертирует в `.toDouble()` (строки 33-34) — теряя точность.

**Риск:** Накопление ошибок округления в скользящем окне из 1M сделок. Особенно критично для альткоинов с ценой <$1.

**Исправление:** Поля `price`/`quantity` должны быть `BigDecimal`. Все расчёты — через `BigDecimal` без преобразования в `Double`.

---

### 🔴 BLOCKER #5: Data Race в `BatchProcessor`

**Файл:** `service/BatchProcessor.kt:37,86`

```kotlin
fun addTrade(trade: Trade) {
    // ...
    if (queue.size >= batchSize) {
        flushBatch(key)     // вызов из потока WebSocket (строка 37)
    }
}

private suspend fun processBatchLoop() {
    while (isRunning) {
        delay(flushIntervalMs)
        tradeQueues.keys.forEach { key ->
            flushBatch(key)  // вызов из корутины-таймера (строка 86)
        }
    }
}
```

`flushBatch()` вызывается из двух мест без синхронизации. Оба делают `queue.poll()` — возможна потеря трейда (два вызова одновременно забрали один и тот же элемент) или двойная вставка.

**Исправление:** Добавить `synchronized` на `flushBatch()`, либо убрать немедленный flush из `addTrade()` и полагаться только на таймер.

---

### 🔴 BLOCKER #6: Data Race в `VolumeFilterProcessor`

**Файл:** `service/VolumeFilterProcessor.kt:21, 29-37`

```kotlin
private val slidingWindows = ConcurrentHashMap<String, SlidingWindowStats>()

data class SlidingWindowStats(
    var volumes: LinkedList<BigDecimal> = LinkedList(),
    var sortedVolumes: MutableList<BigDecimal> = mutableListOf(),
    var totalTrades: Int = 0,
    var volumeThreshold: BigDecimal = BigDecimal.ZERO
)
```

`ConcurrentHashMap` защищает только операции put/get с самой мапой. Поля внутри `SlidingWindowStats` — `var` с изменяемыми коллекциями — модифицируются без синхронизации. При одновременном `processTrade()` (пишет) и `recalculateWindowStats()` (читает/сортирует) — гонка.

**Исправление:** 
1. Сделать `SlidingWindowStats` иммутабельным (data class без `var`)
2. Обернуть доступ к каждому окну в `Mutex` на ключ инструмента

---

### 🔴 CRITICAL #7: Config загружается дважды

**Файл:** `Main.kt:39,50`
```kotlin
val configLoaded = ConfigManager.loadFromFile()       // загрузка 1 — поиск по 6 путям
// ...
ConfigManager.loadFromFile(configPath)                 // загрузка 2 — только "config.json"
```

Первый вызов ищет config.json по 6 путям и парсит его. Второй вызов ищет только `"config.json"` от текущей директории. Если первый нашёл файл, а второй — нет, `config.exchanges` станет пустым списком (дефолт `emptyList()`). Сервис запустится без бирж.

Плюс `configPath = "config.json"`, но реальный файл называется `config/production.json`. Конфиг вообще не загрузится, если не создать `config.json` в корне.

**Исправление:** Убрать дублирующийся вызов. Передавать путь `config/production.json` в `ConfigManager`.

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
| 1.1 | Удалить пароль из `production.json`, сменить пароль на сервере | 30 мин |
| 1.2 | Убрать IP из `AppConfig.kt` | 10 мин |
| 1.3 | Заменить `Double` на `BigDecimal` в `Trade` и всех расчётах | 2 ч |
| 1.4 | Исправить data race в `BatchProcessor` (synchronized на flushBatch) | 1 ч |
| 1.5 | Исправить data race в `VolumeFilterProcessor` (Mutex на окно) | 3 ч |
| 1.6 | Написать минимальный набор тестов | 8 ч |

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
| 🔴 BLOCKER | 6 |
| 🔴 CRITICAL | 9 |
| 🟡 MEDIUM | 6 |
| **Всего** | **21** |

**Оценка трудозатрат:** ~50 человеко-часов (2 недели для одного разработчика).

**Текущий статус после rollback'а:** Проект стал более сырым — отсутствуют DeadLetterQueue и DiskBuffer, больше закомментированного кода. Основные блокеры (пароль, IP, Double, data races) не изменились.
