package com.aandios.service

import com.aandios.model.Trade
import com.aandios.storage.postgres.TradeDAO
import mu.KotlinLogging
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

class BatchProcessor(
    private val dao: TradeDAO,
    private val batchSize: Int = 1000,
    private val flushIntervalMs: Long = 1000
) {
    private val tradeQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<Trade>>()
    private var isRunning = false
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (isRunning) return

        isRunning = true
        job = scope.launch {
            processBatchLoop()
        }

        log.info { "✅ BatchProcessor запущен (batchSize=$batchSize, flushIntervalMs=$flushIntervalMs)" }
    }

    fun addTrade(trade: Trade) {
        val key = "${trade.exchange}_${trade.symbol}"
        val queue = tradeQueues.getOrPut(key) { ConcurrentLinkedQueue() }
        queue.add(trade)

        // Если очередь достигла размера батча - немедленная отправка
        if (queue.size >= batchSize) {
            flushBatch(key)
        }
    }

    fun getQueueSize(instrumentKey: String): Int {
        return tradeQueues[instrumentKey]?.size ?: 0
    }

    fun getTotalQueueSize(): Int {
        return tradeQueues.values.sumOf { it.size }
    }

    private suspend fun processBatchLoop() {
        while (isRunning) {
            delay(flushIntervalMs)
            tradeQueues.keys.forEach { key ->
                flushBatch(key)
            }
        }
    }

    private fun flushBatch(key: String) {
        val queue = tradeQueues[key] ?: return
        val batch = mutableListOf<Trade>()

        while (batch.size < batchSize && queue.isNotEmpty()) {
            val trade = queue.poll()
            if (trade != null) {
                batch.add(trade)
            }
        }

        if (batch.isNotEmpty()) {
            try {
                dao.insertRawTradesBatch(batch)
                log.debug { "✅ Вставлено ${batch.size} тиков в raw_trades для $key" }
            } catch (e: Exception) {
                log.error(e) { "❌ Ошибка вставки батча в raw_trades" }
                // Возвращаем тики обратно в очередь для повторной попытки
                batch.forEach { trade -> queue.offer(trade) }
            }
        }

        // Удаляем пустую очередь
        if (queue.isEmpty()) {
            tradeQueues.remove(key)
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()

        // Финальный flush оставшихся тиков
        tradeQueues.keys.forEach { key ->
            flushBatch(key)
        }

        log.info { "✅ BatchProcessor остановлен" }
    }
}