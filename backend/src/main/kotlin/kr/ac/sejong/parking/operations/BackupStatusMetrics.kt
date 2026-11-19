package kr.ac.sejong.parking.operations

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.scheduling.annotation.Scheduled
import java.nio.file.Files
import java.nio.file.Path

/** Local decrypt/restore acceptance and external upload/source-restore checks are distinct gauges. */
class BackupStatusMetrics(private val path: Path, private val mapper: ObjectMapper, registry: MeterRegistry) {
    @Volatile private var verified = 0.0
    @Volatile private var valid = 0.0
    @Volatile private var externalUpload = 0.0
    @Volatile private var externalSource = 0.0
    init {
        Gauge.builder("parking.backup.last.verified.timestamp.seconds", this) { it.verified }.register(registry)
        Gauge.builder("parking.backup.status.valid", this) { it.valid }.register(registry)
        Gauge.builder("parking.backup.external.upload.timestamp.seconds", this) { it.externalUpload }.register(registry)
        Gauge.builder("parking.backup.external.source.restore.valid", this) { it.externalSource }.register(registry)
    }
    @Scheduled(fixedDelay = 5000)
    fun refresh() {
        verified = 0.0; valid = 0.0; externalUpload = 0.0; externalSource = 0.0
        try {
            check(Files.isRegularFile(path) && Files.size(path) <= 16_384)
            val report = mapper.readTree(Files.readAllBytes(path))
            if (report.path("result").asText() == "BACKUP_UPLOADED_SOURCE_RESTORE_VERIFIED") {
                val uploaded = report.path("completed_at").asDouble(Double.NaN)
                check(uploaded.isFinite() && uploaded > 0 && uploaded <= System.currentTimeMillis() / 1000.0 + 5 &&
                    report.path("source_database_restore_verified").asBoolean(false) &&
                    report.path("version_id").asText().isNotBlank() && report.path("version_id").asText() != "null" &&
                    report.path("ciphertext_sha256").asText().matches(Regex("[a-f0-9]{64}")))
                externalUpload = uploaded; externalSource = 1.0
                return
            }
            val timestamp = report.path("verified_at").asDouble(Double.NaN)
            check(report.path("result").asText() == "BACKUP_VERIFIED_LOCAL" && timestamp.isFinite() && timestamp > 0 &&
                timestamp <= System.currentTimeMillis() / 1000.0 + 5)
            verified = timestamp; valid = 1.0
        } catch (_: Exception) { verified = 0.0; valid = 0.0 }
    }
}
