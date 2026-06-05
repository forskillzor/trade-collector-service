package com.aandios.service

import ch.qos.logback.core.AppenderBase
import ch.qos.logback.classic.spi.ILoggingEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DashboardLogAppender : AppenderBase<ILoggingEvent>() {
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    override fun append(event: ILoggingEvent) {
        val time = fmt.format(Instant.ofEpochMilli(event.timeStamp))
        val level = event.level.toString().take(4)
        val logger = event.loggerName.substringAfterLast(".").take(12)
        val msg = event.formattedMessage.take(200)
        LogCapture.append("$time $level $logger $msg")
    }
}
