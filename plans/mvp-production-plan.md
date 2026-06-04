# MVP Production Readiness Plan — Trade Terminal (Footprint + MarketDelta)

**Date:** 2026-06-04  
**Context:** MVP торгового терминала на одном VPS (1 CPU, 2GB, 40GB). Данные TradeCollectorService → PostgreSQL → footprint/marketdelta.

---

## Оценка аудита (35 пунктов) по принципам Илона Маска

### ✅ Delete (нерелевантно для MVP)

32 из 35 пунктов исключены: партиции, Docker, Prometheus, JMX, тесты, docs, синхронизация корутин, jitter, staging, etc.

### ✅ Already Fixed (аудит устарел)

| # | Что | Статус |
|---|-----|--------|
| 3 | JVM heap 512MB → 768MB | ✅ |
| 9 | /health проверяет DB + WS | ✅ |
| 10 | flushAll time-modulo race | ✅ |
| 28 | prepare-package config path | ✅ |
| 29 | ExecStartPre pg_isready | ✅ |
| 32 | Combined stream для Binance | ✅ |

---

## Изменения

### 1. `config/config.prod.json` — monitoring host → localhost

`"host": "0.0.0.0"` → `"host": "localhost"`

Мониторинг доступен через `ssh -L 8080:localhost:8080 vps`.

### 2. `build.gradle.kts` — синхронизация версий

`version = "1.0-SNAPSHOT"` → `version = "2.0.0"` (Main.kt уже `2.0.0`)

### 3. `ExchangeClient.kt` — дебаг-логи combined stream → DEBUG

`log.warn` → `log.debug` для первых 5 фреймов всех типов (TEXT, PING, PONG, BINARY). Оставлен `log.warn` только для детектора тишины (нет фреймов за 10 сек).

### 4. Аудит — итоговая таблица

| Аудит | Закрыто | Пропущено (MVP) |
|-------|---------|-----------------|
| 5 CRITICAL | #3 (heap) | #1 (localhost), #2 (partitions), #4 (disk buffer limit), #5 (creds) |
| 8 HIGH | #9, #10, #32 | #6‑8, #11‑13 |
| 12 MEDIUM | #19, #21‑22 | #14‑18, #20, #23‑25 |
| 10 LOW | #28‑29, #31 | #26‑27, #30, #33‑35 |

**Итог:** 35 пунктов аудита рассмотрены. 32 исключены как нерелевантные для MVP. 3 активных пункта в плане.
