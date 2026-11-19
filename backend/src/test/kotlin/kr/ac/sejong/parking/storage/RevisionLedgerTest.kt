package kr.ac.sejong.parking.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kr.ac.sejong.parking.device.adapter.jdbc.RevisionLedger
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/** Actual authenticated HTTP against the repository's LOCAL_ONLY adapter, never a mock ledger. */
class RevisionLedgerTest {
    @TempDir lateinit var temp: Path
    private val mapper = ObjectMapper()

    @Test fun `Kotlin long nodes survive real CAS and stale writers cannot replace the head`() {
        RevisionLedgerFixture(temp, mapper).use { fixture ->
            val client = fixture.client()
            val original = client.head()
            val next = mapper.createObjectNode().put("format_version", 1).apply {
                putObject("zones").putObject("zone-01").put("zone_id", "zone-01")
                    .put("manifest_generation", 1L).put("reserved_through", 999_999L)
                    .put("reserved_at", "2026-11-16T00:00:00Z")
            }
            val committed = client.compareAndSet(original, next)
            assertThat(committed.version).isEqualTo(1L)
            assertThat(committed.document).isEqualTo(mapper.readTree(mapper.writeValueAsBytes(next)))
            assertThat(client.requireDocument(next).sha256).isEqualTo(committed.sha256)
            assertThatThrownBy { client.compareAndSet(original, next) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("revision_ledger_unavailable_or_invalid")
            assertThat(client.head()).isEqualTo(committed)
        }
    }

    @Test fun `S3 scope refuses the SQLite reference gateway`() {
        RevisionLedgerFixture(temp, mapper).use { fixture ->
            val token = temp.resolve("s3-token")
            Files.writeString(token, "a".repeat(48))
            val client = RevisionLedger(fixture.uri, fixture.identity, token, mapper, "AWS_S3")
            assertThatThrownBy { client.head() }.hasMessage("revision_ledger_unavailable_or_invalid")
        }
    }

    @Test fun `failed requests log only the exception type without credentials or remote body`() {
        val logger = LoggerFactory.getLogger(RevisionLedger::class.java) as Logger
        val events = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        try {
            RevisionLedgerFixture(temp, mapper).use { fixture ->
                val wrongToken = temp.resolve("diagnostic-token")
                Files.writeString(wrongToken, "b".repeat(48))
                assertThatThrownBy { fixture.client(token = wrongToken).head() }
                    .hasMessage("revision_ledger_unavailable_or_invalid")
                assertThat(events.list.map { it.formattedMessage })
                    .containsExactly("REVISION_LEDGER_REQUEST_FAILED type=IllegalStateException")
                assertThat(events.list.single().throwableProxy).isNull()
            }
        } finally {
            logger.detachAppender(events); events.stop()
        }
    }

    @Test fun `wrong identity bad credentials missing store and outage never yield a fallback head`() {
        RevisionLedgerFixture(temp, mapper).use { fixture ->
            val wrongToken = temp.resolve("wrong-token")
            Files.writeString(wrongToken, "b".repeat(48))
            for (client in listOf(fixture.client(identity = UUID.randomUUID().toString()), fixture.client(token = wrongToken))) {
                assertThatThrownBy { client.head() }.isInstanceOf(IllegalStateException::class.java)
                    .hasMessage("revision_ledger_unavailable_or_invalid")
            }
            val healthy = fixture.client()
            assertThat(healthy.head().version).isZero()
            Files.delete(fixture.database)
            assertThatThrownBy { healthy.head() }.hasMessage("revision_ledger_unavailable_or_invalid")
            assertThat(Files.exists(fixture.database)).isFalse()
            fixture.close()
            assertThatThrownBy { healthy.head() }.hasMessage("revision_ledger_unavailable_or_invalid")
        }
    }
}

/** Shared fixture starts only a temporary SQLite store and a loopback ephemeral HTTP listener. */
internal class RevisionLedgerFixture(directory: Path, private val mapper: ObjectMapper) : AutoCloseable {
    val identity = UUID.randomUUID().toString()
    val database = directory.resolve("ledger.sqlite3")
    private val tokenFile = directory.resolve("ledger-token")
    private val process: Process
    val uri: URI

    init {
        Files.createDirectories(directory)
        Files.writeString(tokenFile, "a".repeat(48))
        val script = Path.of("..").toAbsolutePath().normalize().resolve("infra/backup/ledger.py")
        check(Files.isRegularFile(script)) { "repository_ledger_script_missing" }
        val initialize = ProcessBuilder("python3", script.toString(), "init", "--db", database.toString(),
            "--store-id", identity).redirectErrorStream(true).start()
        check(initialize.waitFor(5, TimeUnit.SECONDS)) { "ledger_fixture_initialization_timeout" }
        check(initialize.exitValue() == 0) { "ledger_fixture_initialization_failed" }
        initialize.inputStream.close()
        val output = directory.resolve("ledger-process.log")
        process = ProcessBuilder("python3", script.toString(), "serve", "--db", database.toString(),
            "--store-id", identity, "--token-file", tokenFile.toString(), "--bind", "127.0.0.1", "--port", "0")
            .redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (Files.size(output) == 0L && process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            val ready = mapper.readTree(Files.readAllBytes(output))
            check(ready?.path("status")?.asText() == "LEDGER_READY") { "ledger_fixture_startup_failed" }
            uri = URI("http://127.0.0.1:${ready["port"].intValue()}")
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    fun client(identity: String = this.identity, token: Path = tokenFile) = RevisionLedger(uri, identity, token, mapper)

    override fun close() {
        if (process.isAlive) {
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(3, TimeUnit.SECONDS)
        }
    }
}
