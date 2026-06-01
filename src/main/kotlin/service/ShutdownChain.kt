package com.aandios.service

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging

private val log = KotlinLogging.logger {}

class ShutdownChain {
    private data class Step(val name: String, val timeoutMs: Long, val block: suspend () -> Unit)
    private val steps = mutableListOf<Step>()

    fun step(name: String, timeoutMs: Long, block: suspend () -> Unit): ShutdownChain {
        steps.add(Step(name, timeoutMs, block))
        return this
    }

    suspend fun execute() {
        steps.forEach { (name, timeoutMs, block) ->
            try {
                withTimeout(timeoutMs) { block() }
                log.info { "stopped: $name" }
            } catch (e: TimeoutCancellationException) {
                log.warn { "timed out: $name (${timeoutMs}ms)" }
            } catch (e: Exception) {
                log.error(e) { "failed: $name" }
            }
        }
    }
}
