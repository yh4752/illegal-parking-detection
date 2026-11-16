package kr.ac.sejong.parking.storage

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ac.sejong.parking.device.adapter.jdbc.RevisionReservations
import kr.ac.sejong.parking.device.application.EventCanonicalizer
import kr.ac.sejong.parking.operations.DemoInitializationRequest
import kr.ac.sejong.parking.operations.FreshDemoInitializer
import kr.ac.sejong.parking.operations.ReadinessProbe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.sql.DataSource

@Tag("database")
@SpringBootTest
@ActiveProfiles("database")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoFoundationDatabaseTest(
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val manager: PlatformTransactionManager,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val dataSource: DataSource,
) {
    companion object {
        private val schema = "it_${UUID.randomUUID().toString().replace("-", "")}"
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.flyway.default-schema") { schema }
            registry.add("spring.datasource.hikari.schema") { schema }
        }
    }
    @TempDir lateinit var temp: Path
    private val request = DemoInitializationRequest("zone-01", "edge-01", 3, "신규 가상 장치 데모")
    private val initializer get() = FreshDemoInitializer(jdbc, TransactionTemplate(manager), mapper)
    private val primary get() = temp.resolve("primary/revisions.json")
    private val backup get() = temp.resolve("backup/revisions.json")
    private fun reservations() = RevisionReservations(jdbc, TransactionTemplate(manager), mapper, primary, backup)
    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!
    private fun hold() = jdbc.queryForObject("SELECT recovery_hold FROM zone_view WHERE zone_id='zone-01'", Boolean::class.java)!!

    @BeforeEach fun freshFixture() {
        jdbc.execute("TRUNCATE zone,app_user CASCADE")
        jdbc.update("INSERT INTO zone(zone_id,display_name) VALUES ('zone-01','시험')")
        jdbc.update("INSERT INTO device(device_id,zone_id,https_token_sha256) VALUES ('edge-01','zone-01',?)", "0".repeat(64))
        jdbc.update("INSERT INTO zone_view(zone_id,device_id) VALUES ('zone-01','edge-01')")
        jdbc.update("INSERT INTO app_user(user_id,username,password_hash,role,enabled) VALUES ('fixture','fixture','!disabled','OPERATOR',false)")
        val content = javaClass.getResourceAsStream("/contracts/examples.json").use { mapper.readTree(it)["config_desired"]["desired_policy"] }
        jdbc.update("""
            INSERT INTO zone_policy(zone_id,version,base_version,change_class,content,content_sha256,created_by,reason)
            VALUES ('zone-01',3,2,'TIMING',?::jsonb,?,'fixture','시험')
        """.trimIndent(), mapper.writeValueAsString(content), EventCanonicalizer(mapper).hash(content))
        jdbc.update("UPDATE zone SET latest_policy_version=3 WHERE zone_id='zone-01'")
    }
    @AfterAll fun cleanup() { jdbc.execute("DROP SCHEMA $schema CASCADE") }

    @Test fun `역할이 OPERATOR여도 구역 권한이 자동 생성되지 않는다`() {
        assertThat(count("user_zone_access")).isZero()
        jdbc.update("INSERT INTO user_zone_access VALUES ('fixture','zone-01')")
        assertThat(count("user_zone_access")).isEqualTo(1L)
        assertThatThrownBy { jdbc.update("INSERT INTO user_zone_access VALUES ('fixture','zone-01')") }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { jdbc.update("INSERT INTO user_zone_access VALUES ('missing','zone-01')") }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { jdbc.update("INSERT INTO user_zone_access VALUES ('fixture','missing')") }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test fun `미관측 측정값은 null이며 fps 소수와 거짓 상태를 보존한다`() {
        val row = jdbc.queryForMap("SELECT fps,pending_message_count,storage_ok FROM zone_view")
        assertThat(row.values).containsOnlyNulls()
        jdbc.update("UPDATE zone_view SET fps=29.97,pending_message_count=9007199254740991,storage_ok=false")
        assertThat(jdbc.queryForObject("SELECT fps FROM zone_view", java.math.BigDecimal::class.java)).isEqualByComparingTo("29.97")
        assertThat(jdbc.queryForObject("SELECT storage_ok FROM zone_view", Boolean::class.java)).isFalse()
    }

    @ParameterizedTest @ValueSource(strings = ["-1", "NaN", "Infinity", "-Infinity"])
    fun `fps의 음수와 비유한 값은 거부한다`(fps: String) {
        assertThatThrownBy { jdbc.update("UPDATE zone_view SET fps=?::numeric", fps) }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test fun `대기 메시지 개수는 계약의 정수 범위를 따른다`() {
        for (value in listOf(-1L, 9_007_199_254_740_992L)) {
            assertThatThrownBy { jdbc.update("UPDATE zone_view SET pending_message_count=?", value) }.isInstanceOf(DataIntegrityViolationException::class.java)
        }
        jdbc.update("UPDATE zone_view SET pending_message_count=0")
        assertThat(jdbc.queryForObject("SELECT pending_message_count FROM zone_view", Long::class.java)).isZero()
    }

    @Test fun `검사는 DB나 예약 파일을 변경하지 않는다`() {
        initializer.inspect(request)
        assertThat(hold()).isTrue(); assertThat(count("audit_entry")).isZero()
        assertThat(jdbc.queryForObject("SELECT revision_range_end FROM zone_view", Long::class.java)).isZero()
        assertThat(Files.exists(primary)).isFalse()
    }

    @Test fun `초기화는 예약을 먼저 남기고 관측 상태를 만들지 않는다`() {
        reservations().use { r -> r.initialize(); initializer.apply(request, r) }
        assertThat(hold()).isFalse(); assertThat(count("audit_entry")).isEqualTo(1L)
        val row = jdbc.queryForMap("SELECT * FROM zone_view")
        assertThat(row["snapshot"]).isNull(); assertThat(row["last_device_seq"]).isEqualTo(-1L)
        assertThat(row["view_revision"]).isEqualTo(0L); assertThat(row["connection_state"]).isEqualTo("OFFLINE")
        val manifest = mapper.readTree(Files.readAllBytes(primary))
        assertThat(manifest["zones"]["zone-01"]["demo_initialization_claimed_at"].isTextual).isTrue()
        assertThat(Files.readAllBytes(primary)).isEqualTo(Files.readAllBytes(backup))
        assertThatThrownBy { initializer.inspect(request) }.hasMessage("zone_already_open")
    }

    @Test fun `조회 gate는 파일 유실 후 복구만으로 자동 재개하지 않는다`() {
        reservations().use { r ->
            r.initialize(); initializer.apply(request, r)
            val end=jdbc.queryForObject("SELECT revision_range_end FROM zone_view",Long::class.java)!!
            r.checkReadable("zone-01",end)
            val bytes=Files.readAllBytes(backup)
            Files.delete(backup)
            assertThatThrownBy { r.checkReadable("zone-01",end) }.isInstanceOf(IllegalStateException::class.java)
            Files.write(backup,bytes)
            assertThatThrownBy { r.checkReadable("zone-01",end) }.isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test fun `운영 readiness는 읽기만 하며 hold와 예약 파일 오류를 숨기지 않는다`() {
        val registry=SimpleMeterRegistry()
        reservations().use { r ->
            r.initialize()
            ReadinessProbe(jdbc,r,registry).use { probe ->
                val original=Files.readAllBytes(primary)
                val row=jdbc.queryForMap("SELECT * FROM zone_view")
                probe.check()
                assertThat(probe.health().status.code).isEqualTo("DOWN")
                assertThat(registry.get("parking.database.available").gauge().value()).isEqualTo(1.0)
                assertThat(Files.readAllBytes(primary)).isEqualTo(original)
                assertThat(jdbc.queryForMap("SELECT * FROM zone_view")).isEqualTo(row)
                initializer.apply(request,r)
                probe.check()
                assertThat(probe.health().status.code).isEqualTo("UP")
                val copy=Files.readAllBytes(backup)
                Files.delete(backup)
                probe.check()
                assertThat(probe.health().status.code).isEqualTo("DOWN")
                Files.write(backup,copy)
                probe.check()
                assertThat(probe.health().status.code).isEqualTo("DOWN")
                assertThat(registry.get("parking.revision.ready").gauge().value()).isEqualTo(0.0)
            }
        }
    }

    @Test fun `수신 이력이 있으면 기존 자료를 보존하고 초기화하지 않는다`() {
        jdbc.update("""INSERT INTO inbound_receipt(device_id,message_id,family,payload_sha256,outcome,first_received_at)
            VALUES ('edge-01','keep','events',?,'STORED',now())""", "a".repeat(64))
        assertThatThrownBy { initializer.inspect(request) }.hasMessage("existing_history:inbound_receipt")
        assertThat(hold()).isTrue(); assertThat(count("inbound_receipt")).isEqualTo(1L)
        assertThat(Files.exists(primary)).isFalse()
    }

    @Test fun `heartbeat 흔적 또는 다른 구역이 있으면 신규로 취급하지 않는다`() {
        jdbc.update("UPDATE zone_view SET last_heartbeat_seq=0")
        assertThatThrownBy { initializer.inspect(request) }.hasMessage("zone_is_not_fresh")
        jdbc.update("UPDATE zone_view SET last_heartbeat_seq=-1")
        jdbc.update("INSERT INTO zone(zone_id,display_name) VALUES ('zone-02','다른 구역')")
        assertThatThrownBy { initializer.inspect(request) }.hasMessage("single_zone_demo_only")
    }

    @Test fun `정책 내용과 등록이 맞지 않으면 초기화를 거부한다`() {
        assertThatThrownBy { initializer.inspect(request.copy(policyVersion = 2)) }.hasMessage("latest_policy_mismatch")
        jdbc.update("UPDATE zone_policy SET content_sha256=?", "f".repeat(64))
        assertThatThrownBy { initializer.inspect(request) }.hasMessage("policy_hash_mismatch")
        assertThatThrownBy { initializer.inspect(request.copy(deviceId = "other")) }.hasMessage("zone_device_mismatch")
        assertThat(hold()).isTrue()
    }

    @Test fun `감사 저장 실패는 hold를 롤백하고 이미 남긴 claim은 복구 검토를 요구한다`() {
        jdbc.execute("ALTER TABLE audit_entry ADD CONSTRAINT deny_init CHECK(action <> 'DEMO_ZONE_INITIALIZED')")
        try {
            reservations().use { r ->
                r.initialize()
                assertThatThrownBy { initializer.apply(request, r) }.isInstanceOf(DataIntegrityViolationException::class.java)
                assertThat(hold()).isTrue(); assertThat(count("audit_entry")).isZero()
                assertThatThrownBy { initializer.apply(request, r) }.hasMessage("initialization_already_claimed_review_required")
            }
        } finally { jdbc.execute("ALTER TABLE audit_entry DROP CONSTRAINT deny_init") }
    }

    @Test fun `최신 manifest를 유지한 채 초기 DB로 되돌려도 초기화 claim을 재사용하지 않는다`() {
        reservations().use { r -> r.initialize(); initializer.apply(request, r) }
        jdbc.update("DELETE FROM audit_entry") // Isolated simulation of a pre-initialization backup, not an operator recipe.
        jdbc.update("UPDATE zone_view SET recovery_hold=true")
        reservations().use { r ->
            r.initialize()
            assertThatThrownBy { initializer.apply(request, r) }.hasMessage("initialization_already_claimed_review_required")
        }
        assertThat(hold()).isTrue()
    }

    @Test fun `claim은 트랜잭션 밖에서 내구 저장하며 기존 파일 잠금과 불일치 방어를 사용한다`() {
        reservations().use { r ->
            r.initialize()
            reservations().use { other -> assertThatThrownBy { other.initialize() }.isInstanceOf(Exception::class.java) }
            r.withFreshInitializationClaim("zone-01") { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse() }
        }
    }

    @Test fun `V2 자료를 유지하면서 V3 V4로 올리고 재적용 시 변경하지 않는다`() {
        val oldSchema = "it_${UUID.randomUUID().toString().replace("-", "")}"
        try {
            Flyway.configure().dataSource(dataSource).defaultSchema(oldSchema).schemas(oldSchema).target("2").load().migrate()
            dataSource.connection.use { connection ->
                val beforeSchema = connection.schema
                try {
                    connection.schema = oldSchema
                    val old = JdbcTemplate(SingleConnectionDataSource(connection, true))
                    old.update("INSERT INTO zone(zone_id,display_name) VALUES ('old-zone','기존')")
                    old.update("INSERT INTO device(device_id,zone_id,https_token_sha256) VALUES ('old-edge','old-zone',?)", "0".repeat(64))
                    old.update("""INSERT INTO zone_view(zone_id,device_id,last_device_seq,snapshot)
                        VALUES ('old-zone','old-edge',12,'{"kept":true}'::jsonb)""")
                    old.update("""INSERT INTO inbound_receipt(device_id,message_id,family,payload_sha256,outcome,first_received_at)
                        VALUES ('old-edge','old-message','events',?,'STORED',now())""", "a".repeat(64))
                    val saved = old.queryForMap("SELECT snapshot,last_device_seq,view_revision,recovery_hold FROM zone_view")
                    val upgraded = Flyway.configure().dataSource(dataSource).defaultSchema(oldSchema).schemas(oldSchema).load()
                    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(2)
                    assertThat(old.queryForMap("SELECT snapshot,last_device_seq,view_revision,recovery_hold FROM zone_view")).isEqualTo(saved)
                    assertThat(old.queryForObject("SELECT outcome FROM inbound_receipt", String::class.java)).isEqualTo("STORED")
                    assertThat(old.queryForObject("SELECT count(*) FROM user_zone_access", Long::class.java)).isZero()
                    assertThat(old.queryForMap("SELECT fps,pending_message_count,storage_ok FROM zone_view").values).containsOnlyNulls()
                    assertThat(upgraded.migrate().migrationsExecuted).isZero()
                    assertThat(upgraded.validateWithResult().validationSuccessful).isTrue()
                } finally { connection.schema = beforeSchema }
            }
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS $oldSchema CASCADE") }
    }
}
