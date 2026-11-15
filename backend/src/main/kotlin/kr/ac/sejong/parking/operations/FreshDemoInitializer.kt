package kr.ac.sejong.parking.operations

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import kr.ac.sejong.parking.device.adapter.jdbc.RevisionReservations
import kr.ac.sejong.parking.device.application.EventCanonicalizer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

data class DemoInitializationRequest(val zoneId: String, val deviceId: String, val policyVersion: Long, val reason: String) {
    init {
        require(listOf(zoneId, deviceId).all { it.isNotBlank() && it.codePointCount(0, it.length) <= 128 && '\u0000' !in it }) {
            "invalid_initialization_identifier"
        }
        require(policyVersion in 1..9_007_199_254_740_991L) { "invalid_policy_version" }
        require(reason.isNotBlank() && reason.codePointCount(0, reason.length) <= 200 && '\u0000' !in reason) {
            "initialization_reason_required"
        }
    }
}

/** Operator-only local tool, not a web endpoint or general recovery acceptance service. */
class FreshDemoInitializer(
    private val jdbc: JdbcTemplate,
    private val transaction: TransactionTemplate,
    private val mapper: ObjectMapper,
) {
    private val historyTables = listOf("incident", "event_message", "inbound_receipt", "inbound_quarantine",
        "command", "server_outbox", "pause_projection", "review", "request_receipt", "device_image",
        "image_upload_intent", "entity_tombstone", "audit_entry")
    private val policySchema = run {
        val canonical = requireNotNull(javaClass.getResourceAsStream("/contracts/parking.schema.json")).use { mapper.readTree(it) }
        val selected = mapper.createObjectNode().apply {
            put("\$schema", canonical["\$schema"].asText()); put("\$ref", "#/\$defs/Policy")
            set<JsonNode>("\$defs", canonical["\$defs"])
        }
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
            selected, SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build(),
        )
    }

    /** No file access and no writes. Apply repeats these checks under the same lock. */
    fun inspect(request: DemoInitializationRequest) {
        transaction.executeWithoutResult { validateLocked(request) }
    }

    /** Caller must hold the ingestion manifest lock. Manifest I/O stays outside the DB transaction. */
    fun apply(request: DemoInitializationRequest, revisions: RevisionReservations) {
        inspect(request)
        revisions.withFreshInitializationClaim(request.zoneId) {
            transaction.executeWithoutResult {
                validateLocked(request)
                val range = jdbc.queryForMap("SELECT revision_range_start,revision_range_end FROM zone_view WHERE zone_id=?", request.zoneId)
                check((range["revision_range_start"] as Number).toLong() == 0L &&
                    (range["revision_range_end"] as Number).toLong() == 999_999L) { "initial_reservation_required" }
                check(jdbc.update("UPDATE zone_view SET recovery_hold=false WHERE zone_id=? AND recovery_hold", request.zoneId) == 1)
                val detail = mapper.createObjectNode().apply {
                    put("reason", request.reason); put("policy_version", request.policyVersion)
                    put("reserved_through", 999_999L); put("source", "local_operator_cli")
                }
                jdbc.update("""
                    INSERT INTO audit_entry(audit_id,zone_id,device_id,action,detail,created_at)
                    VALUES (?,?,?,'DEMO_ZONE_INITIALIZED',?::jsonb,now())
                """.trimIndent(), UUID.randomUUID().toString(), request.zoneId, request.deviceId, mapper.writeValueAsString(detail))
            }
        }
    }

    private fun validateLocked(request: DemoInitializationRequest) {
        val zone = jdbc.queryForList("SELECT * FROM zone_view WHERE zone_id=? FOR UPDATE", request.zoneId).singleOrNull()
        check(zone != null && zone["device_id"] == request.deviceId) { "zone_device_mismatch" }
        val device = jdbc.queryForList("SELECT * FROM device WHERE device_id=? FOR UPDATE", request.deviceId).singleOrNull()
        check(device != null && device["zone_id"] == request.zoneId && device["enabled"] == true) { "device_not_enabled" }
        check(jdbc.queryForObject("SELECT count(*) FROM zone", Long::class.java) == 1L &&
            jdbc.queryForObject("SELECT count(*) FROM device", Long::class.java) == 1L) { "single_zone_demo_only" }
        check(zone["recovery_hold"] == true) { "zone_already_open" }
        check(zone["snapshot"] == null && zone["current_event_id"] == null && zone["received_at"] == null &&
            zone["last_heartbeat_at"] == null && zone["current_boot_id"] == null && zone["current_connection_id"] == null &&
            zone["fps"] == null && zone["pending_message_count"] == null && zone["storage_ok"] == null &&
            zone["connection_state"] == "OFFLINE" && (zone["last_device_seq"] as Number).toLong() == -1L &&
            (zone["last_heartbeat_seq"] as Number).toLong() == -1L && (zone["view_revision"] as Number).toLong() == 0L &&
            (zone["revision_range_start"] as Number).toLong() == 0L &&
            (zone["revision_range_end"] as Number).toLong() in setOf(0L, 999_999L)) { "zone_is_not_fresh" }
        // These names are fixed constants, never supplied by CLI arguments.
        for (table in historyTables) {
            check(jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java) == 0L) { "existing_history:$table" }
        }
        val latest = jdbc.queryForObject("SELECT latest_policy_version FROM zone WHERE zone_id=?", Long::class.java, request.zoneId)
        check(latest == request.policyVersion) { "latest_policy_mismatch" }
        val policy = jdbc.queryForList("SELECT * FROM zone_policy WHERE zone_id=? AND version=?", request.zoneId, request.policyVersion).singleOrNull()
        check(policy != null) { "policy_missing" }
        val content = mapper.readTree(policy["content"].toString())
        check(policySchema.validate(content).isEmpty() && content["zone_id"].asText() == request.zoneId &&
            content["version"].longValue() == request.policyVersion &&
            content["base_version"].longValue() == (policy["base_version"] as Number).toLong() &&
            content["change_class"].asText() == policy["change_class"]) { "invalid_initial_policy" }
        check(EventCanonicalizer(mapper).hash(content) == policy["content_sha256"].toString().trim()) { "policy_hash_mismatch" }
    }
}
