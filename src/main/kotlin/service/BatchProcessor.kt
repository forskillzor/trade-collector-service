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

        log.info { "BatchProcessor started | batch=$batchSize flush=${flushIntervalMs}ms" }
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

        // Копируем batch из очереди
        while (batch.size < batchSize && queue.isNotEmpty()) {
            val trade = queue.poll()
            if (trade != null) {
                batch.add(trade)
            }
        }

        if (batch.isNotEmpty()) {
            try {
                dao.insertRawTradesBatch(batch)
                log.debug { "Inserted ${batch.size} trades for $key" }
            } catch (e: Exception) {
                log.error(e) { "Batch insert error" }
                // Возвращаем обратно в ту же очередь
                batch.forEach { trade ->
                    // Проверяем, что очередь ещё существует
                    val currentQueue = tradeQueues.getOrPut(key) { ConcurrentLinkedQueue() }
                    currentQueue.offer(trade)
                }
                return
            }
        }

        // Удаляем пустую очередь только после успешной вставки
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

        log.info { "BatchProcessor stopped" }
    }
}