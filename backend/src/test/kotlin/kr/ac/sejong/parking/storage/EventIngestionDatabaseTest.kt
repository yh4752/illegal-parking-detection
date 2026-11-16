package kr.ac.sejong.parking.storage

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kr.ac.sejong.parking.device.adapter.jdbc.EventTransactionStore
import kr.ac.sejong.parking.device.adapter.jdbc.RevisionReservations
import kr.ac.sejong.parking.device.adapter.mqtt.EventIngestionMessageHandler
import kr.ac.sejong.parking.device.application.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.integration.mqtt.support.MqttHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.messaging.support.MessageBuilder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.*
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Real commits in a private PostgreSQL schema, not a transaction rolled back by the test runner. */
@Tag("database")
@SpringBootTest
@ActiveProfiles("database")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventIngestionDatabaseTest(
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val manager: PlatformTransactionManager,
    @Autowired private val mapper: ObjectMapper,
) {
    companion object {
        private val schema = "it_${UUID.randomUUID().toString().replace("-", "")}"
        private const val topic = "parking/v2/devices/edge-01/events"
        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.flyway.default-schema") { schema }
            registry.add("spring.datasource.hikari.schema") { schema }
        }
    }

    @TempDir lateinit var temp: Path
    private lateinit var revisions: RevisionReservations
    private lateinit var store: EventTransactionStore
    private lateinit var service: EventIngestionService
    private lateinit var clock: MutableClock
    private val examples by lazy { mapper.readTree(javaClass.getResourceAsStream("/contracts/examples.json")) }
    private val primary get() = temp.resolve("primary/reservations.json")
    private val backup get() = temp.resolve("backup/reservations.json")

    @BeforeEach
    fun setup() {
        jdbc.execute("TRUNCATE zone, app_user CASCADE")
        jdbc.update("INSERT INTO zone(zone_id,display_name) VALUES ('zone-01','시험')")
        jdbc.update("INSERT INTO device(device_id,zone_id,https_token_sha256) VALUES ('edge-01','zone-01',?)", "0".repeat(64))
        jdbc.update("INSERT INTO zone_view(zone_id,device_id) VALUES ('zone-01','edge-01')")
        jdbc.update("INSERT INTO app_user(user_id,username,password_hash,role,enabled) VALUES ('fixture','fixture','!disabled','OPERATOR',false)")
        policy(3)
        clock = MutableClock(Instant.parse("2026-11-13T07:00:00Z"))
        revisions = reservation().apply { initialize() }
        // This fixture represents an explicitly opened zone. Recovery holds are tested separately.
        jdbc.update("UPDATE zone_view SET recovery_hold=false")
        store = EventTransactionStore(jdbc, TransactionTemplate(manager), revisions, mapper, clock)
        service = EventIngestionService(EventMessageValidator(mapper) { _, _ -> true }, EventCanonicalizer(mapper), store)
    }

    @AfterEach fun closeGate() { revisions.close() }

    @Test fun `recovery hold blocks new events duplicate ACKs and heartbeat without changing data`() {
        committed(event())
        jdbc.update("UPDATE zone_view SET recovery_hold=true")
        val before = view()
        assertThat(receive(event())).isEqualTo(EventIngestResult.NotAcknowledged("recovery_hold"))
        assertThat(receive(event("new", 12))).isEqualTo(EventIngestResult.NotAcknowledged("recovery_hold"))
        pulse(heartbeat(13))
        assertThat(view()).isEqualTo(before)
        assertThat(count("inbound_receipt")).isEqualTo(1)
    }
    @AfterAll fun cleanup() { jdbc.execute("DROP SCHEMA $schema CASCADE") }

    private fun reservation() = RevisionReservations(jdbc, TransactionTemplate(manager), mapper, primary, backup)
    private fun policy(version: Long) {
        val content = examples["config_desired"]["desired_policy"].deepCopy<ObjectNode>().apply { put("version", version) }
        jdbc.update("""
            INSERT INTO zone_policy(zone_id,version,base_version,change_class,content,content_sha256,created_by,reason)
            VALUES ('zone-01',?,2,'TIMING',?::jsonb,?,'fixture','시험')
        """.trimIndent(), version, mapper.writeValueAsString(content), EventCanonicalizer(mapper).hash(content))
    }
    private fun event(id: String = "m10", seq: Long = 10, eventId: String = "evt-001"): ObjectNode =
        examples["guidance_complete"].deepCopy<ObjectNode>().apply {
            put("message_id", id); put("device_seq", seq); put("event_id", eventId)
            (get("event") as ObjectNode).put("event_id", eventId)
            (get("snapshot") as ObjectNode).put("device_seq", seq).put("current_event_id", eventId)
        }
    private fun closed(id: String = "closed", seq: Long = 20): ObjectNode = event(id, seq).apply {
        put("event_type", "EVENT_CLOSED_EXIT")
        (get("event") as ObjectNode).put("state", "CLOSED_EXIT").put("closed_at", clock.instant().toString()).put("close_reason", "OBSERVED_EXIT")
        (get("snapshot") as ObjectNode).put("incident_state", "CLOSED_EXIT")
    }
    private fun receive(message: JsonNode) = service.receive(topic, mapper.writeValueAsBytes(message))
    private fun committed(message: JsonNode, status: String = "STORED", error: String? = null) {
        assertThat(receive(message)).isEqualTo(EventIngestResult.Committed("edge-01", message["message_id"].asText(), status, error))
    }
    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!
    private fun view() = jdbc.queryForMap("SELECT * FROM zone_view WHERE zone_id='zone-01'")
    private fun send(message: JsonNode, publish: (String, ByteArray) -> Unit) {
        EventIngestionMessageHandler(service::receive, publish, EventAckFactory(mapper, clock)).handleMessage(
            MessageBuilder.withPayload(mapper.writeValueAsBytes(message)).setHeader(MqttHeaders.RECEIVED_TOPIC, topic)
                .setHeader(MqttHeaders.RECEIVED_QOS, 1).setHeader(MqttHeaders.RECEIVED_RETAINED, false).build(),
        )
    }

    @Test fun `transient preflight DB read failure changes no file and allows a checked retry`() {
        revisions.close()
        val spy=org.mockito.Mockito.spy(jdbc)
        revisions=RevisionReservations(spy,TransactionTemplate(manager),mapper,primary,backup).apply { initialize() }
        val before=Files.readAllBytes(primary)
        org.mockito.Mockito.doThrow(org.springframework.jdbc.CannotGetJdbcConnectionException("test outage"))
            .doCallRealMethod().`when`(spy).queryForList("SELECT * FROM zone_view WHERE zone_id=?","zone-01")
        assertThatThrownBy { revisions.withZone("zone-01") { error("must not execute") } }.hasMessage("reservation_database_unavailable")
        assertThat(Files.readAllBytes(primary)).isEqualTo(before);assertThat(Files.readAllBytes(backup)).isEqualTo(before)
        var executed=false;revisions.withZone("zone-01") {executed=true};assertThat(executed).isTrue()
    }

    private fun heartbeat(seq:Long=11):ObjectNode = examples["heartbeat"].deepCopy<ObjectNode>().apply {
        put("device_seq",seq);put("message_id","heartbeat-$seq");(get("snapshot") as ObjectNode).put("device_seq",seq)
    }
    private fun pulse(message:ObjectNode=heartbeat(), retained:Boolean=false,qos:Int=0) {
        kr.ac.sejong.parking.configuration.HeartbeatReceiver(mapper,store).receive(
            "parking/v2/devices/edge-01/heartbeat",mapper.writeValueAsBytes(message),qos,retained)
    }
    @Test fun `heartbeat projects snapshot summary and diagnostics with no receipt or raw history`() {
        pulse()
        assertThat(view()).containsEntry("last_device_seq",11L).containsEntry("last_heartbeat_seq",11L).containsEntry("connection_state","ONLINE").containsEntry("storage_ok",true)
        assertThat(count("incident")).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT started_at FROM incident",Timestamp::class.java)).isNull()
        assertThat(count("event_message")).isZero();assertThat(count("inbound_receipt")).isZero();assertThat(count("inbound_quarantine")).isZero()
    }
    @Test fun `repeated old and retained heartbeat cannot refresh connectivity or replace a newer event`() {
        pulse();val before=view();clock.now=clock.now.plusSeconds(16);store.expireConnectivity()
        pulse();pulse(heartbeat(10));pulse(heartbeat(12),retained=true)
        assertThat(view()).containsEntry("connection_state","STALE").containsEntry("last_heartbeat_at",before["last_heartbeat_at"])
        committed(event("newer-event",20));pulse(heartbeat(19));assertThat(view()).containsEntry("last_device_seq",20L).containsEntry("last_heartbeat_seq",11L)
    }
    @Test fun `heartbeat expiry has strict fifteen and thirty second boundaries and leaves the vehicle snapshot intact`() {
        pulse();val snapshot=view()["snapshot"].toString()
        clock.now=clock.now.plusSeconds(15);store.expireConnectivity();assertThat(view()["connection_state"]).isEqualTo("ONLINE")
        clock.now=clock.now.plusMillis(1);store.expireConnectivity();assertThat(view()["connection_state"]).isEqualTo("STALE")
        clock.now=clock.now.plusMillis(14999);store.expireConnectivity();assertThat(view()["connection_state"]).isEqualTo("STALE")
        clock.now=clock.now.plusMillis(1);store.expireConnectivity();assertThat(view()["connection_state"]).isEqualTo("OFFLINE")
        assertThat(view()["snapshot"].toString()).isEqualTo(snapshot)
        pulse(heartbeat(12));assertThat(view()["connection_state"]).isEqualTo("ONLINE")
    }
    @Test fun `malformed semantic invalid wrong topic disabled device and unknown policy heartbeat make no changes`() {
        val bad=heartbeat().apply {(get("snapshot") as ObjectNode).put("continuous_stop_ms",-1)};pulse(bad)
        pulse(heartbeat().apply{put("device_id","wrong-device")})
        pulse(heartbeat().apply{(get("snapshot") as ObjectNode).put("active_policy_version",99).put("received_policy_version",99)})
        jdbc.update("UPDATE device SET enabled=false");pulse()
        assertThat(view()["last_device_seq"]).isEqualTo(-1L);assertThat(count("incident")).isZero()
    }
    @Test fun `heartbeat cannot reopen a closed event or decrease its guidance count`() {
        committed(closed(seq=20));pulse(heartbeat(21))
        assertThat(view()["last_device_seq"]).isEqualTo(20L);assertThat(view()["last_heartbeat_at"]).isNull()
    }
    @Test fun `delayed reliable event is retained without rolling back newer heartbeat and supplies a missing start time only on a newer summary`() {
        pulse(heartbeat(20));committed(event("delayed",19));assertThat(count("event_message")).isEqualTo(1)
        assertThat(view()["last_device_seq"]).isEqualTo(20L)
        committed(event("new",21));assertThat(view()["last_device_seq"]).isEqualTo(21L)
        assertThat(jdbc.queryForObject("SELECT started_at FROM incident",Timestamp::class.java)).isNotNull()
    }

    @Test fun `원자 저장 후 트랜잭션 밖에서 ACK를 만들며 온라인 상태를 추정하지 않는다`() {
        var sent = false
        send(event()) { ackTopic, bytes ->
            sent = true
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            assertThat(count("event_message")).isEqualTo(1)
            assertThat(ackTopic).isEqualTo("parking/v2/devices/edge-01/acks")
            val ack = mapper.readTree(bytes)
            assertThat(ack.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("schema_version", "device_id", "message_id", "status", "error_code", "committed_at")
            assertThat(ack["status"].asText()).isEqualTo("STORED")
            assertThat(ack["error_code"].isNull).isTrue()
            assertThat(ack["committed_at"].asText()).isEqualTo(clock.instant().toString())
        }
        assertThat(sent).isTrue()
        assertThat(count("incident")).isEqualTo(1)
        assertThat(count("inbound_receipt")).isEqualTo(1)
        assertThat(view()).containsEntry("last_device_seq", 10L).containsEntry("view_revision", 1L)
            .containsEntry("connection_state", "OFFLINE").containsEntry("last_heartbeat_at", null)
        assertThat(jdbc.queryForObject("SELECT started_at FROM incident", Timestamp::class.java)!!.toInstant()).isEqualTo(Instant.parse("2026-11-03T03:00:00Z"))
    }

    @Test fun `키 순서 공백 숫자 표기가 다른 동일 메시지는 한 번만 저장한다`() {
        val original = event()
        committed(original)
        val reversed = mapper.createObjectNode().apply {
            original.properties().toList().reversed().forEach { set<JsonNode>(it.key, it.value) }
            put("device_seq", 10.0)
        }
        clock.now = clock.now.plusSeconds(60)
        committed(reversed, "DUPLICATE")
        assertThat(count("event_message")).isEqualTo(1)
        assertThat(view()["view_revision"]).isEqualTo(1L)
        assertThat(jdbc.queryForObject("SELECT first_received_at FROM inbound_receipt", Timestamp::class.java)!!.toInstant()).isEqualTo(Instant.parse("2026-11-13T07:00:00Z"))
    }

    @Test fun `내용이 다른 동일 ID는 원래 receipt를 보존하고 충돌을 격리한다`() {
        committed(event())
        committed(event(seq = 11), "REJECTED", "MESSAGE_ID_CONFLICT")
        committed(event(seq = 11), "REJECTED", "MESSAGE_ID_CONFLICT")
        committed(event(), "DUPLICATE")
        assertThat(count("inbound_quarantine")).isEqualTo(1)
        assertThat(count("event_message")).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT outcome FROM inbound_receipt", String::class.java)).isEqualTo("STORED")
        assertThat(view()["last_device_seq"]).isEqualTo(10L)
    }

    @Test fun `이미 거부된 메시지는 정책을 나중에 등록해도 같은 거부 결과를 재현한다`() {
        val missing = event().apply {
            (get("event") as ObjectNode).put("event_policy_version", 4)
            (get("snapshot") as ObjectNode).put("event_policy_version", 4).put("received_policy_version", 4).put("active_policy_version", 4)
        }
        committed(missing, "REJECTED", "VERSION_CONFLICT")
        policy(4)
        committed(missing, "REJECTED", "VERSION_CONFLICT")
        assertThat(count("event_message")).isZero()
        assertThat(count("inbound_quarantine")).isEqualTo(1)
    }

    @Test fun `역순 메시지는 원문만 남기고 현재 상태를 되돌리지 않는다`() {
        committed(event("new", 12))
        committed(event("old", 10))
        assertThat(count("event_message")).isEqualTo(2)
        assertThat(view()["last_device_seq"]).isEqualTo(12L)
        assertThat(view()["view_revision"]).isEqualTo(1L)
        assertThat(jdbc.queryForObject("SELECT last_device_seq FROM incident", Long::class.java)).isEqualTo(12L)
    }

    @Test fun `과거 사건 종료와 다른 현재 사건을 독립 갱신한다`() {
        committed(event("b", 30, "evt-B"))
        val historical = closed("a", 20).apply { set<JsonNode>("snapshot", event("unused", 20, "evt-B")["snapshot"]) }
        committed(historical)
        assertThat(jdbc.queryForObject("SELECT state FROM incident WHERE event_id='evt-001'", String::class.java)).isEqualTo("CLOSED_EXIT")
        assertThat(view()).containsEntry("last_device_seq", 30L).containsEntry("current_event_id", "evt-B")
    }

    @Test fun `종료한 사건의 재개와 안내 횟수 감소는 전체 메시지를 거부한다`() {
        committed(closed())
        committed(event("reopen", 21), "REJECTED", "INVALID_SCHEMA")
        val lower = closed("lower", 22).apply {
            (get("event") as ObjectNode).put("guidance_count", 0)
            (get("snapshot") as ObjectNode).put("guidance_count", 0)
        }
        committed(lower, "REJECTED", "INVALID_SCHEMA")
        committed(event("past", 10))
        assertThat(view()["last_device_seq"]).isEqualTo(20L)
        assertThat(jdbc.queryForObject("SELECT state FROM incident", String::class.java)).isEqualTo("CLOSED_EXIT")
    }

    @Test fun `사건 정책은 최신 보고로 바꾸지 못한다`() {
        committed(event())
        policy(4)
        val changed = event("changed", 11).apply {
            (get("event") as ObjectNode).put("event_policy_version", 4)
            (get("snapshot") as ObjectNode).put("event_policy_version", 4).put("received_policy_version", 4)
        }
        committed(changed, "REJECTED", "VERSION_CONFLICT")
        assertThat(view()["last_device_seq"]).isEqualTo(10L)
    }

    @Test fun `snapshot만 받은 사건은 알 수 없는 시각을 null로 보존한다`() {
        val message = event().apply { putNull("event"); put("event_type", "DEVICE_RESTARTED") }
        committed(message)
        assertThat(jdbc.queryForMap("SELECT started_at,closed_at FROM incident")).containsEntry("started_at", null).containsEntry("closed_at", null)
    }

    @Test fun `사진 메타데이터는 아직 저장 성공으로 응답하지 않는다`() {
        val image = examples["automatic_photo_available"]
        assertThat(receive(image)).isEqualTo(EventIngestResult.NotAcknowledged("unsupported_image_status"))
        assertThat(count("inbound_receipt")).isZero()
    }

    @Test fun `상관 불가 토픽 불일치 비활성 장치에는 ACK가 없다`() {
        assertThat(service.receive(topic, "{".toByteArray())).isInstanceOf(EventIngestResult.NotAcknowledged::class.java)
        assertThat(receive(event().apply { remove("message_id") })).isInstanceOf(EventIngestResult.NotAcknowledged::class.java)
        assertThat(service.receive("parking/v2/devices/other/events", mapper.writeValueAsBytes(event()))).isInstanceOf(EventIngestResult.NotAcknowledged::class.java)
        jdbc.update("UPDATE device SET enabled=false")
        assertThat(receive(event())).isEqualTo(EventIngestResult.NotAcknowledged("device_registration"))
        assertThat(count("inbound_receipt")).isZero()
    }

    @Test fun `같은 사건의 모순과 지원하지 않는 버전은 별도 오류로 격리한다`() {
        val inconsistent = event().apply { (get("event") as ObjectNode).put("continuous_stop_ms", 0) }
        committed(inconsistent, "REJECTED", "EVENT_MISMATCH")
        committed(event("version").apply { put("schema_version", 3) }, "REJECTED", "UNSUPPORTED_SCHEMA")
        assertThat(count("incident")).isZero()
        assertThat(count("inbound_quarantine")).isEqualTo(2)
    }

    @Test fun `동시 재전송은 하나만 저장한다`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val outcomes = executor.invokeAll(List(2) { Callable { receive(event()) as EventIngestResult.Committed } }).map { it.get().status }
            assertThat(outcomes).containsExactlyInAnyOrder("STORED", "DUPLICATE")
            assertThat(count("event_message")).isEqualTo(1)
        } finally { executor.shutdownNow() }
    }

    @Test fun `커밋 시점 실패는 모든 저장을 취소하고 ACK하지 않는다`() {
        jdbc.execute("""CREATE FUNCTION fail_commit() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''test commit failure''; END'""")
        jdbc.execute("""CREATE CONSTRAINT TRIGGER fail_commit AFTER INSERT ON inbound_receipt DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_commit()""")
        try {
            send(event()) { _, _ -> fail<Unit>("ACK must not precede commit") }
            for (table in listOf("inbound_receipt", "event_message", "incident")) assertThat(count(table)).isZero()
            assertThat(view()["last_device_seq"]).isEqualTo(-1L)
        } finally {
            jdbc.execute("DROP TRIGGER fail_commit ON inbound_receipt")
            jdbc.execute("DROP FUNCTION fail_commit()")
        }
        committed(event())
    }

    @Test fun `ACK 전송 실패는 커밋을 취소하지 않으며 재전송은 DUPLICATE다`() {
        send(event()) { _, _ -> throw IllegalStateException("simulated ACK loss") }
        assertThat(count("event_message")).isEqualTo(1)
        var status: String? = null
        send(event()) { _, bytes -> status = mapper.readTree(bytes)["status"].asText() }
        assertThat(status).isEqualTo("DUPLICATE")
        assertThat(count("event_message")).isEqualTo(1)
    }

    @Test fun `만료 원문을 지워도 영수증과 tombstone은 중복과 부활을 막는다`() {
        val message = closed()
        val conflicting = closed(seq = 21)
        committed(message)
        committed(conflicting, "REJECTED", "MESSAGE_ID_CONFLICT")
        clock.now = clock.now.plus(Duration.ofDays(31))
        store.purgeExpired()
        assertThat(count("event_message")).isZero()
        assertThat(count("incident")).isZero()
        assertThat(count("entity_tombstone")).isEqualTo(1)
        committed(message, "DUPLICATE")
        committed(conflicting, "REJECTED", "MESSAGE_ID_CONFLICT")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbound_quarantine WHERE raw_payload IS NOT NULL", Long::class.java)).isZero()
        committed(closed("late", 21))
        committed(event("reopen", 22), "REJECTED", "INVALID_SCHEMA")
        assertThat(count("incident")).isZero()
    }

    @Test fun `번호 예약은 재시작에 재사용하고 소진하면 다음 범위를 먼저 확보한다`() {
        assertThat(view()["revision_range_end"]).isEqualTo(999_999L)
        revisions.close()
        revisions = reservation().apply { initialize() }
        assertThat(view()["revision_range_end"]).isEqualTo(999_999L)
        jdbc.update("UPDATE zone_view SET view_revision=revision_range_end")
        revisions.withZone("zone-01") { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse() }
        assertThat(view()).containsEntry("revision_range_start", 1_000_000L).containsEntry("revision_range_end", 1_999_999L)
        assertThat(mapper.readTree(Files.readAllBytes(primary))).isEqualTo(mapper.readTree(Files.readAllBytes(backup)))
    }

    @Test fun `예약 파일 유실과 복사본 불일치는 새 번호를 추정하지 않고 중단한다`() {
        revisions.close()
        val bytes = Files.readAllBytes(backup)
        Files.delete(backup)
        assertThatThrownBy { reservation().initialize() }.hasMessageContaining("manifest_copy_missing")
        Files.writeString(backup, "{}")
        assertThatThrownBy { reservation().initialize() }.hasMessageContaining("manifest_copies_disagree")
        Files.write(backup, bytes)
        Files.delete(primary); Files.delete(backup)
        assertThatThrownBy { reservation().initialize() }.hasMessageContaining("manifest_missing_for_existing_state")
    }

    @Test fun `DB만 이전 번호 범위로 되돌리면 재시작을 거부한다`() {
        revisions.close()
        jdbc.update("UPDATE zone_view SET view_revision=0,revision_range_start=0,revision_range_end=0")
        assertThatThrownBy { reservation().initialize() }.hasMessageContaining("manifest_db_range_mismatch")
    }

    @Test fun `사용자용 등록 SQL은 원본 정책과 같고 반복해도 기존 데이터를 바꾸지 않는다`() {
        jdbc.execute("TRUNCATE zone, app_user CASCADE")
        repeat(2) {
            jdbc.execute(Files.readString(Path.of("../infra/sql/register-local-device.sql")))
            jdbc.execute(Files.readString(Path.of("../infra/sql/register-local-policy.sql")))
        }
        val row = jdbc.queryForMap("SELECT * FROM zone_policy")
        val content = mapper.readTree(row["content"].toString())
        assertThat(content).isEqualTo(examples["config_desired"]["desired_policy"])
        assertThat(row["content_sha256"].toString().trim()).isEqualTo(EventCanonicalizer(mapper).hash(content))
        assertThat(jdbc.queryForObject("SELECT enabled FROM app_user", Boolean::class.java)).isFalse()
        assertThat(count("zone_policy")).isEqualTo(1)
    }

    @Test fun `다른 장치의 사건 ID는 덮어쓰지 않는다`() {
        committed(event())
        jdbc.update("INSERT INTO zone(zone_id,display_name) VALUES ('zone-02','다른 시험')")
        jdbc.update("INSERT INTO device(device_id,zone_id,https_token_sha256) VALUES ('edge-02','zone-02',?)", "0".repeat(64))
        jdbc.update("INSERT INTO zone_view(zone_id,device_id,recovery_hold) VALUES ('zone-02','edge-02',false)")
        jdbc.update("""INSERT INTO zone_policy(zone_id,version,base_version,change_class,content,content_sha256,created_by,reason)
            SELECT 'zone-02',version,base_version,change_class,content,content_sha256,created_by,reason FROM zone_policy WHERE zone_id='zone-01'""")
        val other = event("other", 11).apply { put("device_id", "edge-02"); put("zone_id", "zone-02") }
        assertThat(service.receive("parking/v2/devices/edge-02/events", mapper.writeValueAsBytes(other)))
            .isEqualTo(EventIngestResult.Committed("edge-02", "other", "REJECTED", "EVENT_MISMATCH"))
        assertThat(jdbc.queryForObject("SELECT device_id FROM incident", String::class.java)).isEqualTo("edge-01")
        assertThat(jdbc.queryForObject("SELECT last_device_seq FROM zone_view WHERE zone_id='zone-02'", Long::class.java)).isEqualTo(-1L)
    }

    @Test fun `최신 snapshot의 pause 전체 목록을 반영하고 빠진 항목은 해제한다`() {
        val manual = examples["manual_close_event"].deepCopy<ObjectNode>()
        committed(manual)
        assertThat(jdbc.queryForObject("SELECT status FROM pause_projection", String::class.java)).isEqualTo("ACK_REQUIRED")
        committed(event("next", 21, "evt-next"))
        assertThat(jdbc.queryForMap("SELECT status,cleared_seen_at,last_device_seq FROM pause_projection"))
            .containsEntry("status", "CLEARED").containsEntry("last_device_seq", 21L)
        committed(manual.apply { put("message_id", "late-manual") })
        assertThat(jdbc.queryForObject("SELECT status FROM pause_projection", String::class.java)).isEqualTo("CLEARED")
    }

    @Test fun `오류 격리 커밋이 실패해도 거부 ACK를 먼저 보내지 않는다`() {
        jdbc.execute("""CREATE FUNCTION fail_quarantine() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''test quarantine failure''; END'""")
        jdbc.execute("""CREATE CONSTRAINT TRIGGER fail_quarantine AFTER INSERT ON inbound_quarantine DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_quarantine()""")
        val invalid = event().apply { put("schema_version", 3) }
        try {
            send(invalid) { _, _ -> fail<Unit>("Rejected ACK must not precede quarantine commit") }
            assertThat(count("inbound_quarantine")).isZero()
            assertThat(count("inbound_receipt")).isZero()
        } finally {
            jdbc.execute("DROP TRIGGER fail_quarantine ON inbound_quarantine")
            jdbc.execute("DROP FUNCTION fail_quarantine()")
        }
        committed(invalid, "REJECTED", "UNSUPPORTED_SCHEMA")
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
