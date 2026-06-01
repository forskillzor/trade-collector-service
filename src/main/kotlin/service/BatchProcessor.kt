package com.aandios.service

import com.aandios.model.Trade
import com.aandios.storage.postgres.TradeDAO
import mu.KotlinLogging
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

private class CircuitBreaker(
    private val failureThreshold: Int = 3,
    private val resetTimeoutMs: Long = 30_000
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    var state = State.CLOSED
        private set
    private var failureCount = 0
    private var lastFailureTime = 0L

    fun isCallAllowed(): Boolean {
        return when (state) {
            State.CLOSED -> true
            State.OPEN -> {
                if (System.currentTimeMillis() - lastFailureTime > resetTimeoutMs) {
                    state = State.HALF_OPEN
                    log.info { "Circuit Breaker → HALF_OPEN" }
                    true
                } else {
                    false
                }
            }
            State.HALF_OPEN -> true
        }
    }

    fun onSuccess() {
        if (state != State.CLOSED) {
            log.info { "Circuit Breaker → CLOSED" }
        }
        state = State.CLOSED
        failureCount = 0
    }

    fun onFailure() {
        failureCount++
        lastFailureTime = System.currentTimeMillis()
        when {
            state == State.HALF_OPEN -> {
                state = State.OPEN
                log.warn { "Circuit Breaker → OPEN" }
            }
            state == State.CLOSED && failureCount >= failureThreshold -> {
                state = State.OPEN
                log.warn { "Circuit Breaker → OPEN after $failureCount failures" }
            }
        }
    }
}

class BatchProcessor(
    private val dao: TradeDAO,
    private val batchSize: Int = 1000,
    private val flushIntervalMs: Long = 1000
) {
    private val tradeQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<Trade>>()
    private val circuitBreaker = CircuitBreaker()
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

    private fun flushBatch(key: String) = synchronized(tradeQueues) {
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
            if (!circuitBreaker.isCallAllowed()) {
                log.warn { "Circuit Breaker OPEN — dropping ${batch.size} trades for $key" }
            } else {
                try {
                    dao.insertRawTradesBatch(batch)
                    circuitBreaker.onSuccess()
                    log.debug { "Inserted ${batch.size} trades for $key" }
                } catch (e: Exception) {
                    log.error(e) { "Batch insert error" }
                    circuitBreaker.onFailure()
                    batch.forEach { trade ->
                        val currentQueue = tradeQueues.getOrPut(key) { ConcurrentLinkedQueue() }
                        currentQueue.offer(trade)
                    }
                    return
                }
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