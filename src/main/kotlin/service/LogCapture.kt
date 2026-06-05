package com.aandios.service

import java.util.concurrent.ConcurrentLinkedQueue

object LogCapture {
    private val maxLines = 200
    private val buffer = ConcurrentLinkedQueue<String>()

    fun append(msg: String) {
        buffer.add(msg)
        while (buffer.size > maxLines) buffer.poll()
    }

    fun getLines(): List<String> = buffer.toList()
}
