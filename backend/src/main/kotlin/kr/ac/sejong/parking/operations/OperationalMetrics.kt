package kr.ac.sejong.parking.operations

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Timer
import org.springframework.dao.DataAccessException
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class MqttChannel(val label: String) { EVENTS("events"), ACKS("acks"), TELEMETRY("telemetry") }

/** Fixed-cardinality operational signals. Never accepts IDs, reasons, topics or payloads as tags. */
class OperationalMetrics(private val registry: MeterRegistry) {
    private val outcomes = listOf("stored", "duplicate", "rejected", "not_acknowledged", "failed")
        .associateWith { registry.counter("parking.ingest", "outcome", it) }
    private val ack = listOf("success", "failure").associateWith { registry.counter("parking.ack.publish", "result", it) }
    private val commitFailures = registry.counter("parking.commit.failures")
    private val commit = Timer.builder("parking.ingest.commit")
        .description("Event processing through completed DB commit; excludes ACK publication")
        .serviceLevelObjectives(*listOf(10L, 50, 100, 250, 500, 1000, 2500, 5000).map(Duration::ofMillis).toTypedArray())
        .register(registry)
    private val mqtt = MqttChannel.entries.associateWith { channel ->
        AtomicInteger().also { value ->
            Gauge.builder("parking.mqtt.connected", value) { it.get().toDouble() }.tag("channel", channel.label).register(registry)
        }
    }
    private val sseRejected = registry.counter("parking.sse.rejected")
    private val sseSlow = registry.counter("parking.sse.slow.closed")

    fun notAcknowledged() { outcomes.getValue("not_acknowledged").increment() }
    fun failed(error: Exception? = null) {
        outcomes.getValue("failed").increment()
        if (generateSequence<Throwable>(error) { it.cause }.take(16).any { it is DataAccessException || it is SQLException })
            commitFailures.increment()
    }
    fun committed(status: String, elapsedNanos: Long) {
        val outcome = when(status) { "STORED" -> "stored"; "DUPLICATE" -> "duplicate"; "REJECTED" -> "rejected"; else -> "failed" }
        outcomes.getValue(outcome).increment()
        if (outcome != "failed") commit.record(elapsedNanos, TimeUnit.NANOSECONDS)
    }
    fun ackPublished(success: Boolean) { ack.getValue(if(success) "success" else "failure").increment() }
    fun mqttConnected(channel: MqttChannel, connected: Boolean) { mqtt.getValue(channel).set(if(connected) 1 else 0) }
    fun observeStreams(active: () -> Int) {
        Gauge.builder("parking.sse.active", active) { it().toDouble() }.strongReference(true).register(registry)
    }
    fun sseRejected() { sseRejected.increment() }
    fun sseSlowClosed() { sseSlow.increment() }
}
