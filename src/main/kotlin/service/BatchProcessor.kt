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
    private val flushIntervalMs: Long = 1000,
    private val diskBuffer: DiskBuffer? = null
) {
    private val tradeQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<Trade>>()
    private val flushLocks = ConcurrentHashMap<String, Any>()
    private val circuitBreaker = CircuitBreaker()
    private var isRunning = false
    private var job: Job? = null
    private var flushScope: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        if (isRunning) return

        isRunning = true
        flushScope = scope

        if (diskBuffer?.hasPending() == true) {
            log.info { "Replaying disk buffer..." }
            diskBuffer.replayTo(dao)
        }

        job = scope.launch {
            processBatchLoop()
        }

        log.info { "BatchProcessor started | batch=$batchSize flush=${flushIntervalMs}ms" }
    }

    fun addTrade(trade: Trade) {
        val key = "${trade.exchange}_${trade.symbol}"
        val queue = tradeQueues.getOrPut(key) { ConcurrentLinkedQueue() }
        queue.add(trade)

        if (queue.size >= batchSize) {
            val scope = flushScope
            if (scope != null) {
                scope.launch { flushBatch(key) }
            } else {
                flushBatch(key)
            }
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
        val lock = flushLocks.getOrPut(key) { Any() }
        synchronized(lock) {
            val queue = tradeQueues[key] ?: return
            val batch = mutableListOf<Trade>()

            while (batch.size < batchSize && queue.isNotEmpty()) {
                val trade = queue.poll()
                if (trade != null) {
                    batch.add(trade)
                }
            }

            if (batch.isNotEmpty()) {
                if (!circuitBreaker.isCallAllowed()) {
                    log.warn { "Circuit Breaker OPEN — saving ${batch.size} trades to disk for $key" }
                    diskBuffer?.saveBatch(batch)
                } else {
                    try {
                        dao.insertRawTradesBatch(batch)
                        circuitBreaker.onSuccess()
                    } catch (e: Exception) {
                        log.error(e) { "Batch insert error for $key" }
                        circuitBreaker.onFailure()
                        batch.forEach { trade ->
                            val currentQueue = tradeQueues.getOrPut(key) { ConcurrentLinkedQueue() }
                            currentQueue.offer(trade)
                        }
                        return
                    }
                }
            }

            if (queue.isEmpty()) {
                tradeQueues.remove(key)
                flushLocks.remove(key)
            }
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()
        flushScope = null

        tradeQueues.keys.forEach { key ->
            flushBatch(key)
        }

        log.info { "BatchProcessor stopped" }
    }
}