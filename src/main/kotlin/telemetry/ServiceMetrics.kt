package com.aandios.telemetry

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

class ServiceMetrics {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    init {
        JvmMemoryMetrics().bindTo(registry)
        JvmGcMetrics().bindTo(registry)
        JvmThreadMetrics().bindTo(registry)
        ClassLoaderMetrics().bindTo(registry)
        ProcessorMetrics().bindTo(registry)
    }

    fun gauge(name: String, description: String, supplier: () -> Number) {
        Gauge.builder(name) { supplier().toDouble() }
            .description(description)
            .register(registry)
    }

    fun scrape(): String = registry.scrape()
}
