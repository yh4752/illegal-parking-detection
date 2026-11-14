package kr.ac.sejong.parking.storage

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kr.ac.sejong.parking.device.adapter.jdbc.JdbcDeviceRegistrationLookup

@Tag("database")
@SpringBootTest
@ActiveProfiles("database")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class DatabaseMigrationTest(
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val flyway: Flyway,
) {
    companion object {
        // Each run owns a fresh schema; no test cleans the application's public schema.
        private val schema = "it_${UUID.randomUUID().toString().replace("-", "")}"

        @JvmStatic
        @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.flyway.default-schema") { schema }
            registry.add("spring.datasource.hikari.schema") { schema }
        }
    }

    @BeforeEach
    fun registeredDevice() {
        jdbc.update("INSERT INTO zone(zone_id, display_name) VALUES ('zone-01', '시험 구역'), ('zone-02', '다른 구역')")
        jdbc.update("""
            INSERT INTO device(device_id, zone_id, https_token_sha256)
            VALUES ('edge-01', 'zone-01', ?)
        """.trimIndent(), "0".repeat(64))
    }

    @AfterAll
    fun removeOnlyThisTestsSchema() {
        jdbc.execute("DROP SCHEMA $schema CASCADE")
    }

    @Test
    fun `빈 스키마에 전체 테이블을 생성하고 다시 적용해도 변경하지 않는다`() {
        val tables = jdbc.queryForList(
            "SELECT tablename FROM pg_tables WHERE schemaname = ?", String::class.java, schema,
        )
        assertThat(tables).containsExactlyInAnyOrder(
            "flyway_schema_history", "app_user", "zone", "device", "zone_policy",
            "incident", "zone_view", "inbound_receipt", "event_message", "command",
            "server_outbox", "pause_projection", "review", "request_receipt",
            "device_image", "image_upload_intent", "entity_tombstone", "audit_entry",
            "inbound_quarantine", "user_zone_access",
        )
        assertThat(flyway.migrate().migrationsExecuted).isZero()
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue()
    }

    @Test
    fun `처음 등록한 구역은 미확인 상태를 유지한다`() {
        jdbc.update("INSERT INTO zone_view(zone_id, device_id) VALUES ('zone-01', 'edge-01')")
        val row = jdbc.queryForMap("SELECT * FROM zone_view WHERE zone_id = 'zone-01'")
        assertThat(row["connection_state"]).isEqualTo("OFFLINE")
        assertThat(row["snapshot"]).isNull()
        assertThat(row["last_heartbeat_at"]).isNull()
        assertThat(row["last_device_seq"]).isEqualTo(-1L)
        assertThat(row["view_revision"]).isEqualTo(0L)
        assertThat(row["recovery_hold"]).isEqualTo(true)
    }

    @Test
    fun `같은 장치와 메시지 ID의 수신 기록을 중복 저장하지 못한다`() {
        val sql = """
            INSERT INTO inbound_receipt(device_id, message_id, family, payload_sha256, outcome, first_received_at)
            VALUES ('edge-01', 'msg-010', 'events', ?, 'STORED', now())
        """.trimIndent()
        jdbc.update(sql, "a".repeat(64))
        assertThatThrownBy { jdbc.update(sql, "a".repeat(64)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `장치가 속하지 않은 구역의 현재 상태를 만들지 못한다`() {
        assertThatThrownBy {
            jdbc.update("INSERT INTO zone_view(zone_id, device_id) VALUES ('zone-02', 'edge-01')")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `수신 이력 없이 원문만 저장하지 못한다`() {
        assertThatThrownBy {
            jdbc.update("""
                INSERT INTO event_message(device_id, message_id, zone_id, device_seq, received_at, payload)
                VALUES ('edge-01', 'msg-010', 'zone-01', 10, now(), '{}'::jsonb)
            """.trimIndent())
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `등록 조회는 장치 구역 활성 여부를 모두 검사한다`() {
        val lookup = JdbcDeviceRegistrationLookup(jdbc)
        assertThat(lookup.isEnabled("edge-01", "zone-01")).isTrue()
        assertThat(lookup.isEnabled("edge-01", "zone-02")).isFalse()
        assertThat(lookup.isEnabled("unknown", "zone-01")).isFalse()
        jdbc.update("UPDATE device SET enabled = false WHERE device_id = 'edge-01'")
        assertThat(lookup.isEnabled("edge-01", "zone-01")).isFalse()
    }

    @Test
    fun `설계의 트랜잭션 격리 수준과 제한 시간을 사용한다`() {
        assertThat(jdbc.queryForObject("SHOW transaction_isolation", String::class.java)).isEqualTo("read committed")
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String::class.java)).isEqualTo("1s")
        assertThat(jdbc.queryForObject("SHOW statement_timeout", String::class.java)).isEqualTo("3s")
    }
}
