package kr.ac.sejong.parking.operations

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BackupStatusMetricsTest {
    @TempDir lateinit var temp: Path
    @Test fun `missing failed corrupt and future reports cannot imply verified backup`() {
        val registry = SimpleMeterRegistry()
        val file = temp.resolve("status.json")
        val metrics = BackupStatusMetrics(file, ObjectMapper(), registry)
        metrics.refresh()
        assertThat(registry.get("parking.backup.status.valid").gauge().value()).isZero()
        for (body in listOf("broken", "{}", """{"result":"DUMPED","verified_at":1}""",
            """{"result":"BACKUP_VERIFIED_LOCAL","verified_at":99999999999}""")) {
            Files.writeString(file, body); metrics.refresh()
            assertThat(registry.get("parking.backup.last.verified.timestamp.seconds").gauge().value()).isZero()
        }
        Files.writeString(file, """{"result":"BACKUP_VERIFIED_LOCAL","verified_at":1700000000}""")
        metrics.refresh()
        assertThat(registry.get("parking.backup.status.valid").gauge().value()).isEqualTo(1.0)
        assertThat(registry.get("parking.backup.last.verified.timestamp.seconds").gauge().value()).isEqualTo(1700000000.0)
        Files.delete(file); metrics.refresh()
        assertThat(registry.get("parking.backup.status.valid").gauge().value()).isZero()
    }
}
