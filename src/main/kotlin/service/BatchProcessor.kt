/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.service

import com.aandios.model.Trade
import com.aandios.storage.postgres.TradeDAO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val log = KotlinLogging.logger {}

private class CircuitBreaker(
    private val failureThreshold: Int = 3,
    private val resetTimeoutMs: Long = 30_000
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    @Volatile
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

private class TradeQueue {
    private val items = ArrayDeque<Trade>()
    private val lock = Any()

    fun offer(trade: Trade) {
        synchronized(lock) { items.addLast(trade) }
    }

    fun size(): Int = synchronized(lock) { items.size }

    fun drain(max: Int): List<Trade> {
        synchronized(lock) {
            val count = minOf(max, items.size)
            if (count == 0) return emptyList()
            val batch = ArrayList<Trade>(count)
            repeat(count) { batch.add(items.removeFirst()) }
            return batch
        }
    }

    fun prependAll(batch: List<Trade>) {
        synchronized(lock) {
            for (i in batch.indices.reversed()) {
                items.addFirst(batch[i])
            }
        }
    }
}

class BatchProcessor(
    private val dao: TradeDAO,
    private val batchSize: Int = 1000,
    private val flushIntervalMs: Long = 1000,
    private val diskBuffer: DiskBuffer? = null,
    private val maxQueueSize: Int = 250_000
) {
    private val tradeQueues = ConcurrentHashMap<String, TradeQueue>()
    private val totalSize = AtomicInteger(0)
    private val circuitBreaker = CircuitBreaker()
    private val flushLock = Any()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var isRunning = false
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (isRunning) return

        isRunning = true

        if (diskBuffer?.hasPending() == true) {
            log.info { "Replaying disk buffer..." }
            diskBuffer.replayTo(dao)
        }

        job = scope.launch(Dispatchers.IO) { processBatchLoop() }

        log.info {
            "BatchProcessor started | batch=$batchSize flush=${flushIntervalMs}ms " +
                "maxQueue=$maxQueueSize"
        }
    }

    fun addTrade(trade: Trade): Boolean {
        if (totalSize.incrementAndGet() > maxQueueSize) {
            totalSize.decrementAndGet()
            return false
        }

        if (!isRunning) {
            val queue = tradeQueues.getOrPut(trade.key) { TradeQueue() }
            queue.offer(trade)
            if (queue.size() >= batchSize) {
                synchronized(flushLock) { flushBatch(trade.key, queue) }
            }
            return true
        }

        val queue = tradeQueues.getOrPut(trade.key) { TradeQueue() }
        queue.offer(trade)

        if (queue.size() >= batchSize) {
            wakeUp.trySend(Unit)
        }
        return true
    }

    fun getQueueSize(instrumentKey: String): Int {
        return tradeQueues[instrumentKey]?.size() ?: 0
    }

    fun getTotalQueueSize(): Int = totalSize.get()

    private suspend fun processBatchLoop() {
        while (isRunning) {
            val hasFullQueue = tradeQueues.values.any { it.size() >= batchSize }
            if (hasFullQueue) {
                val flushed = synchronized(flushLock) { flushAll(onlyFull = true) }
                if (!flushed) delay(flushIntervalMs)
                continue
            }

            val signaled = withTimeoutOrNull(flushIntervalMs) { wakeUp.receive() }
            if (signaled == null) {
                synchronized(flushLock) { flushAll(onlyFull = false) }
            }
        }
    }

    private fun flushAll(onlyFull: Boolean): Boolean {
        var success = true
        tradeQueues.forEach { (key, queue) ->
            if (onlyFull && queue.size() < batchSize) return@forEach
            if (!flushBatch(key, queue)) success = false
        }
        return success
    }

    private fun flushBatch(key: String, queue: TradeQueue): Boolean {
        val batch = queue.drain(batchSize)
        if (batch.isEmpty()) return true

        if (!circuitBreaker.isCallAllowed()) {
            log.warn { "Circuit Breaker OPEN — saving ${batch.size} trades to disk for $key" }
            return persistOrRequeue(key, queue, batch)
        }

        return try {
            dao.insertRawTradesBatch(batch)
            circuitBreaker.onSuccess()
            totalSize.addAndGet(-batch.size)
            true
        } catch (e: Exception) {
            log.error(e) { "Batch insert error for $key" }
            circuitBreaker.onFailure()
            persistOrRequeue(key, queue, batch)
        }
    }

    private fun persistOrRequeue(key: String, queue: TradeQueue, batch: List<Trade>): Boolean {
        if (diskBuffer != null && diskBuffer.saveBatch(batch)) {
            totalSize.addAndGet(-batch.size)
            log.warn { "Saved ${batch.size} trades to disk WAL for $key" }
            return true
        }

        queue.prependAll(batch)
        log.warn { "Re-queued ${batch.size} trades for $key after DB/WAL failure" }
        return false
    }

    fun stop() {
        isRunning = false
        job?.cancel()
        job = null

        synchronized(flushLock) {
            do {
                val flushed = flushAll(onlyFull = false)
                if (!flushed) break
            } while (tradeQueues.values.any { it.size() > 0 })
        }

        log.info { "BatchProcessor stopped" }
    }
}
