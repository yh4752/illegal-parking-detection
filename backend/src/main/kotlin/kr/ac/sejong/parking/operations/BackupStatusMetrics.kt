package kr.ac.sejong.parking.operations

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.scheduling.annotation.Scheduled
import java.nio.file.Files
import java.nio.file.Path

/** Only a completed decrypt/restore/compare/fence report counts. File I/O is never on the scrape thread. */
class BackupStatusMetrics(private val path: Path, private val mapper: ObjectMapper, registry: MeterRegistry) {
    @Volatile private var verified = 0.0
    @Volatile private var valid = 0.0
    init {
        Gauge.builder("parking.backup.last.verified.timestamp.seconds", this) { it.verified }.register(registry)
        Gauge.builder("parking.backup.status.valid", this) { it.valid }.register(registry)
    }
    @Scheduled(fixedDelay = 5000)
    fun refresh() {
        try {
            check(Files.isRegularFile(path) && Files.size(path) <= 16_384)
            val report = mapper.readTree(Files.readAllBytes(path))
            val timestamp = report.path("verified_at").asDouble(Double.NaN)
            check(report.path("result").asText() == "BACKUP_VERIFIED_LOCAL" && timestamp.isFinite() && timestamp > 0 &&
                timestamp <= System.currentTimeMillis() / 1000.0 + 5)
            verified = timestamp; valid = 1.0
        } catch (_: Exception) { verified = 0.0; valid = 0.0 }
    }
}
