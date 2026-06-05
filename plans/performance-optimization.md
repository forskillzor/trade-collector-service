# Performance Analysis — Trade Collector v2.0

**Date**: 2026-06-05  
**VPS**: 1-core, 1.8GB RAM  
**Load**: 100% CPU at 180-200 TPS (20 symbols, 2 timeframes)  
**Root cause**: Per-trade overhead of BigDecimal allocations, Instant creation, ConcurrentHashMap operations

---

## Hotspot Counts (per single trade, 2 timeframes)

| Category | Count / trade | At 100 TPS (objects/sec) |
|---|---|---|
| BigDecimal new objects | 16 | **1,600/sec** |
| BigDecimal operations | ~18 | 1,800/sec |
| Instant creations | 3 | 300/sec |
| JSON readTree() calls | 1 | 100/sec |
| synchronized lock acquisitions | 1 | 100/sec |
| ConcurrentHashMap reads/writes | 13 | 1,300/sec |
| String key allocations | 4 | 400/sec |

---

## Optimization Plan (ranked by CPU savings)

| # | Optimization | Est. Saving | File(s) |
|---|---|---|---|
| 1 | BigDecimal → Double in VolumeFilterProcessor EWMA + chunks | **25-40%** | VolumeFilterProcessor.kt |
| 2 | Eliminate Instant in calculateCandleStart() + updateTps() | **8-15%** | AggregateProcessor.kt, TradeProcessor.kt |
| 3 | Cache composite key on Trade object | **5-10%** | Trade.kt, VolFilter, AggProcessor, TradeProcessor |
| 4 | ConcurrentHashMap → HashMap (single-threaded) | **5-8%** | VolFilter, AggProcessor, TradeProcessor |
| 5 | Remove synchronized lock | **2-5%** | VolumeFilterProcessor.kt |
| 6 | Streaming JSON parser (single-pass, no tree) | **2-4%** | BinanceAdapter.kt |
| 7 | BigDecimal → Double in AggregateProcessor volumes | **1-3%** | PriceLevelData.kt, AggProcessor.kt |
| 8 | JVM flags: -XX:+UseSerialGC | **5-10%** | run.sh |

**Estimated total: 50-75% CPU reduction → 25-50% utilization on 1-core VPS**
