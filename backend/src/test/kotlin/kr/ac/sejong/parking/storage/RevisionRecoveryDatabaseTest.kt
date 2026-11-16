package kr.ac.sejong.parking.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kr.ac.sejong.parking.device.adapter.jdbc.EventTransactionStore
import kr.ac.sejong.parking.device.adapter.jdbc.RevisionLedger
import kr.ac.sejong.parking.device.adapter.jdbc.RevisionReservations
import kr.ac.sejong.parking.device.application.EventCanonicalizer
import kr.ac.sejong.parking.operations.RevisionRecovery
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Tag("database")
@SpringBootTest
@ActiveProfiles("database")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RevisionRecoveryDatabaseTest(
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val manager: PlatformTransactionManager,
    @Autowired private val mapper: ObjectMapper,
) {
    companion object {
        private val schema = "it_${UUID.randomUUID().toString().replace("-", "")}"
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.flyway.default-schema") { schema }
            registry.add("spring.datasource.hikari.schema") { schema }
        }
    }

    @TempDir lateinit var temp: Path
    private lateinit var fixture: RevisionLedgerFixture
    private lateinit var ledger: RevisionLedger
    private val primary get() = temp.resolve("primary/revisions.json")
    private val backup get() = temp.resolve("backup/revisions.json")
    private val transaction get() = TransactionTemplate(manager)
    private val recovery get() = RevisionRecovery(jdbc, transaction, mapper, primary, backup, ledger)
    private val examples by lazy { mapper.readTree(javaClass.getResourceAsStream("/contracts/examples.json")) }

    @BeforeEach fun freshFixture() {
        jdbc.execute("TRUNCATE zone,app_user,audit_entry CASCADE")
        jdbc.update("INSERT INTO zone(zone_id,display_name) VALUES ('zone-01','복구 시험')")
        jdbc.update("INSERT INTO device(device_id,zone_id,https_token_sha256) VALUES ('edge-01','zone-01',?)", "0".repeat(64))
        jdbc.update("INSERT INTO zone_view(zone_id,device_id) VALUES ('zone-01','edge-01')")
        jdbc.update("INSERT INTO app_user(user_id,username,password_hash,role,enabled) VALUES ('fixture','fixture','!disabled','OPERATOR',false)")
        val content = examples["config_desired"]["desired_policy"]
        jdbc.update("""INSERT INTO zone_policy(zone_id,version,base_version,change_class,content,content_sha256,created_by,reason)
            VALUES ('zone-01',3,2,'TIMING',?::jsonb,?,'fixture','복구 시험')""",
            mapper.writeValueAsString(content), EventCanonicalizer(mapper).hash(content))
        jdbc.update("UPDATE zone SET latest_policy_version=3 WHERE zone_id='zone-01'")
        fixture = RevisionLedgerFixture(temp.resolve("independent"), mapper)
        ledger = fixture.client()
    }

    @AfterEach fun stopLedger() { if (::fixture.isInitialized) fixture.close() }
    @AfterAll fun cleanupSchema() { jdbc.execute("DROP SCHEMA $schema CASCADE") }

    private fun reservations(withLedger: Boolean = true) = RevisionReservations(jdbc, transaction, mapper, primary, backup,
        if (withLedger) ledger else null)
    private fun initialize(withLedger: Boolean = true) { reservations(withLedger).use { it.initialize() } }
    private fun view() = jdbc.queryForMap("SELECT * FROM zone_view WHERE zone_id='zone-01'")
    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!
    private fun parsed(document: ObjectNode) = mapper.readTree(mapper.writeValueAsBytes(document)) as ObjectNode
    private fun writePair(document: ObjectNode) {
        Files.createDirectories(primary.parent); Files.createDirectories(backup.parent)
        Files.write(primary, mapper.writeValueAsBytes(document)); Files.write(backup, mapper.writeValueAsBytes(document))
    }
    private fun advanced(document: ObjectNode, end: Long, generation: Long) = document.deepCopy().apply {
        (get("zones")["zone-01"] as ObjectNode).put("reserved_through", end).put("manifest_generation", generation)
    }
    private fun event(id: String, sequence: Long): ObjectNode = examples["guidance_complete"].deepCopy<ObjectNode>().apply {
        put("message_id", id); put("device_seq", sequence)
        (get("snapshot") as ObjectNode).put("device_seq", sequence)
    }
    private fun reconcile(id: String = "recovered-10", sequence: Long = 10) =
        recovery.reconcile(mapper.writeValueAsBytes(event(id, sequence)))

    @Test fun `rolling back both local files and DB cannot hide the latest independent claim`() {
        initialize()
        val before = Files.readAllBytes(primary)
        reservations().use { gate ->
            gate.initialize()
            gate.withFreshInitializationClaim("zone-01") { jdbc.update("UPDATE zone_view SET recovery_hold=false") }
        }
        Files.write(primary, before); Files.write(backup, before)
        jdbc.update("UPDATE zone_view SET recovery_hold=true,view_revision=0")
        assertThatThrownBy { initialize() }.hasMessage("revision_ledger_mismatch")
        assertThat(ledger.head().document["zones"]["zone-01"].has("demo_initialization_claimed_at")).isTrue()
        assertThat(view()["recovery_hold"]).isEqualTo(true)
    }

    @Test fun `crashes after remote commit or local copies fail closed until DB agrees and missing copies still block`() {
        initialize()
        val old = ledger.head()
        val next = advanced(old.document, 1_999_999, 2)
        ledger.compareAndSet(old, next) // Crash window: durable remote version, old local pair and DB.
        assertThatThrownBy { initialize() }.hasMessage("revision_ledger_mismatch")
        writePair(next) // Crash window: both files advanced, DB commit has not happened.
        assertThatThrownBy { initialize() }.hasMessage("manifest_db_range_mismatch")
        jdbc.update("UPDATE zone_view SET revision_range_start=1000000,revision_range_end=1999999,view_revision=1000000")
        initialize()
        assertThat(view()["recovery_hold"]).isEqualTo(true)
        Files.delete(backup)
        assertThatThrownBy { initialize() }.hasMessage("manifest_copy_missing")
    }

    @Test fun `prepare reserves above remote local and database maxima while preserving an ahead initialization claim`() {
        initialize()
        val first = advanced(ledger.head().document, 1_999_999, 2)
        val second = advanced(first, 2_999_999, 3)
        (second["zones"]["zone-01"] as ObjectNode).put("demo_initialization_claimed_at", "2026-11-16T01:00:00Z")
        Files.write(primary, mapper.writeValueAsBytes(first)); Files.write(backup, mapper.writeValueAsBytes(second))
        jdbc.update("""UPDATE zone_view SET revision_range_end=3999999,view_revision=3123000,
            recovery_hold=false,connection_state='ONLINE',last_heartbeat_at=now(),fps=30,pending_message_count=2,storage_ok=true""")
        val invalid = second.deepCopy()
        (invalid["zones"]["zone-01"] as ObjectNode).put("reserved_through", "9007199254740991")
        Files.write(backup, mapper.writeValueAsBytes(invalid))
        val oldHead = ledger.head()
        assertThatThrownBy { recovery.reserve("prepare", "잘못된 숫자 형식 거부") }.isInstanceOf(IllegalStateException::class.java)
        assertThat(ledger.head()).isEqualTo(oldHead)
        Files.write(backup, mapper.writeValueAsBytes(second))
        val report = recovery.reserve("prepare", "명시적 복원 시험")
        assertThat(report["stage"].asText()).isEqualTo("PREPARED")
        assertThat(view()).containsEntry("revision_range_start", 4_000_000L).containsEntry("revision_range_end", 4_999_999L)
            .containsEntry("view_revision", 4_000_000L).containsEntry("recovery_hold", true).containsEntry("connection_state", "OFFLINE")
        assertThat(view()["last_heartbeat_at"]).isNull()
        assertThat(view()["fps"]).isNull()
        val zone = ledger.head().document["zones"]["zone-01"]
        assertThat(zone["manifest_generation"].longValue()).isEqualTo(4L)
        assertThat(zone["demo_initialization_claimed_at"].asText()).isEqualTo("2026-11-16T01:00:00Z")
        assertThat(Files.readAllBytes(primary)).isEqualTo(Files.readAllBytes(backup))
        assertThat(mapper.readTree(Files.readAllBytes(primary))).isEqualTo(ledger.head().document)
    }

    @Test fun `prepare never substitutes local files for an unavailable latest independent record`() {
        initialize()
        val before = view()
        val bytes = Files.readAllBytes(primary)
        fixture.close()
        assertThatThrownBy { recovery.reserve("prepare", "저장소 중단 시험") }
            .hasMessage("revision_ledger_unavailable_or_invalid")
        assertThat(view()).isEqualTo(before)
        assertThat(Files.readAllBytes(primary)).isEqualTo(bytes)
        assertThat(Files.readAllBytes(backup)).isEqualTo(bytes)
    }

    @Test fun `reconcile validates fresh reports and held background work cannot change state even at range exhaustion`() {
        initialize()
        recovery.reserve("prepare", "장치 대조 시험")
        val invalid = event("invalid-report", 9).apply { put("schema_version", 999) }
        assertThatThrownBy { recovery.reconcile(mapper.writeValueAsBytes(invalid)) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(view()["last_device_seq"]).isEqualTo(-1L)
        val report = reconcile()
        assertThat(report["stage"].asText()).isEqualTo("RECONCILED")
        assertThat(report["automatic_commands_published"].booleanValue()).isFalse()
        assertThat(view()).containsEntry("last_device_seq", 10L).containsEntry("recovery_hold", true)
        assertThat(count("event_message")).isEqualTo(1L)
        assertThat(count("server_outbox")).isZero()
        assertThat(count("command")).isZero()
        assertThatThrownBy { reconcile("stale-report", 10) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(count("event_message")).isEqualTo(1L)
        reservations().use { gate ->
            gate.initialize()
            jdbc.update("UPDATE zone_view SET view_revision=revision_range_end,connection_state='ONLINE',last_heartbeat_at=now()-interval '1 hour'")
            jdbc.update("UPDATE event_message SET received_at=now()-interval '40 days'")
            val before = view()
            val head = ledger.head()
            val store = EventTransactionStore(jdbc, transaction, gate, mapper)
            store.expireConnectivity(); store.purgeExpired()
            assertThat(view()).isEqualTo(before)
            assertThat(ledger.head()).isEqualTo(head)
            assertThat(count("event_message")).isEqualTo(1L)
        }
    }

    @Test fun `accept rejects stale evidence and opens only the explicit current file roundtrip report`() {
        initialize()
        recovery.reserve("prepare", "복구 인수 시험")
        val stale = parsed(reconcile())
        jdbc.update("UPDATE zone SET display_name='변경 감지'")
        assertThatThrownBy { recovery.accept(stale, "오래된 결과 거부") }.hasMessage("recovery_report_stale")
        assertThat(view()["recovery_hold"]).isEqualTo(true)
        val current = parsed(reconcile("recovered-11", 11))
        val result = recovery.accept(current, "장치와 손실 범위 확인 완료")
        assertThat(result["stage"].asText()).isEqualTo("ACCEPTED")
        assertThat(view()["recovery_hold"]).isEqualTo(false)
        assertThat(ledger.head().document["zones"]["zone-01"].has("recovery_accepted_report")).isTrue()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_entry WHERE action='RECOVERY_ACCEPTED'", Long::class.java)).isEqualTo(1L)
        assertThat(count("server_outbox")).isZero()
    }

    @Test fun `relabeling a prepared report cannot bypass device reconciliation`() {
        initialize()
        val forged = parsed(recovery.reserve("prepare", "대조 우회 방지 시험"))
            .put("stage", "RECONCILED").put("device_report_sha256", "a".repeat(64))
        val head = ledger.head()
        assertThatThrownBy { recovery.accept(forged, "대조 없는 인수") }.isInstanceOf(IllegalStateException::class.java)
        assertThat(view()["recovery_hold"]).isEqualTo(true)
        assertThat(ledger.head()).isEqualTo(head)
        assertThat(count("event_message")).isZero()
    }

    @Test fun `existing data adoption requires an unused store and matching files and database`() {
        initialize(withLedger = false)
        jdbc.update("""INSERT INTO inbound_receipt(device_id,message_id,family,payload_sha256,outcome,first_received_at)
            VALUES ('edge-01','existing-history','events',?,'STORED',now())""", "a".repeat(64))
        val bytes = Files.readAllBytes(backup)
        Files.delete(backup)
        assertThatThrownBy { recovery.reserve("adopt", "기존 자료 인수") }.hasMessage("adoption_requires_healthy_local_pair")
        Files.write(backup, bytes)
        jdbc.update("UPDATE zone_view SET revision_range_end=1999999")
        assertThatThrownBy { recovery.reserve("adopt", "기존 자료 인수") }.hasMessage("adoption_database_mismatch")
        jdbc.update("UPDATE zone_view SET revision_range_end=999999")
        val report = recovery.reserve("adopt", "검토한 기존 자료 인수")
        assertThat(report["stage"].asText()).isEqualTo("PREPARED")
        assertThat(view()).containsEntry("revision_range_start", 1_000_000L).containsEntry("revision_range_end", 1_999_999L)
            .containsEntry("recovery_hold", true)
        assertThat(count("inbound_receipt")).isEqualTo(1L)
        assertThatThrownBy { recovery.reserve("adopt", "중복 인수 거부") }.hasMessage("adoption_requires_unused_independent_store")
    }
}
