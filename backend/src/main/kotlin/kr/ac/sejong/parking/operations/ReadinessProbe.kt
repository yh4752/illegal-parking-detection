package kr.ac.sejong.parking.operations

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import kr.ac.sejong.parking.device.adapter.jdbc.RevisionReservations
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Cached read-only probe: scrapes never wait for DB/file I/O. MQTT is deliberately excluded. */
class ReadinessProbe(private val jdbc: JdbcTemplate, private val revisions: RevisionReservations, registry: MeterRegistry)
    : HealthIndicator, AutoCloseable {
    private data class Sample(val database: Double, val revision: Double, val checkedAt: Long)
    @Volatile private var sample = Sample(0.0, Double.NaN, 0)
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "parking-readiness-probe").apply { isDaemon=true }
    }
    init {
        Gauge.builder("parking.database.available", this) { it.sample.database }.register(registry)
        Gauge.builder("parking.revision.ready", this) { it.sample.revision }.register(registry)
        Gauge.builder("parking.readiness.ready", this) { if(it.ready()) 1.0 else 0.0 }.register(registry)
        Gauge.builder("parking.readiness.checked.at", this) { it.sample.checkedAt / 1000.0 }.register(registry)
    }
    fun start() { worker.scheduleWithFixedDelay(::check, 0, 5, TimeUnit.SECONDS) }
    fun check() {
        val rows = try { jdbc.queryForList("SELECT zone_id, revision_range_end, recovery_hold FROM zone_view") }
            catch (_: Exception) { sample=Sample(0.0, Double.NaN, System.currentTimeMillis()); return }
        val readable = try {
            rows.isNotEmpty() && rows.all { row ->
                revisions.checkReadable(row["zone_id"] as String, (row["revision_range_end"] as Number).toLong())
                row["recovery_hold"] == false
            }
        } catch (_: Exception) { false }
        sample=Sample(1.0, if(readable) 1.0 else 0.0, System.currentTimeMillis())
    }
    private fun ready() = sample.database == 1.0 && sample.revision == 1.0 &&
        System.currentTimeMillis()-sample.checkedAt < 15_000
    override fun health(): Health = if(ready()) Health.up().build() else Health.down().build()
    override fun close() { worker.shutdownNow() }
}
