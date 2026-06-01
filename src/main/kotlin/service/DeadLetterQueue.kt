package com.aandios.service

import mu.KotlinLogging
import java.io.File

private val log = KotlinLogging.logger {}

class DeadLetterQueue(private val dataDir: String, private val maxEntries: Int = 1000) {
    private val dqlFile: File

    init {
        File(dataDir).mkdirs()
        dqlFile = File(dataDir, "dead_letter.jsonl")
    }

    @Synchronized
    fun push(rawMessage: String, exchange: String, symbol: String, error: String) {
        try {
            val timestamp = System.currentTimeMillis()
            val escapedMessage = rawMessage
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
            val escapedError = error
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")

            dqlFile.bufferedWriter().use { writer ->
                writer.appendLine(
                    """{"ts":$timestamp,"exchange":"$exchange","symbol":"$symbol","error":"$escapedError","message":"$escapedMessage"}"""
                )
            }

            val lines = dqlFile.readLines()
            if (lines.size > maxEntries) {
                val keep = lines.takeLast(maxEntries)
                dqlFile.writeText(keep.joinToString("\n") + "\n")
            }

            log.debug { "DLQ: $exchange/$symbol — $error" }
        } catch (e: Exception) {
            log.error(e) { "DLQ write error" }
        }
    }

    @Synchronized
    fun drain(): List<String> {
        if (!dqlFile.exists()) return emptyList()
        val entries = dqlFile.readLines()
        dqlFile.delete()
        return entries
    }

    @Synchronized
    fun size(): Int {
        if (!dqlFile.exists()) return 0
        return dqlFile.readLines().size
    }
}
