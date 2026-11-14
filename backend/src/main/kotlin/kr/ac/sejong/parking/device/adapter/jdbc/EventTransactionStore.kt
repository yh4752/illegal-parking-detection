package kr.ac.sejong.parking.device.adapter.jdbc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kr.ac.sejong.parking.device.application.CorrelatedDeviceMessage
import kr.ac.sejong.parking.device.application.EventCanonicalizer
import kr.ac.sejong.parking.device.application.EventIngestResult
import kr.ac.sejong.parking.device.application.EventValidationResult
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/** All events writes use zone_view -> device -> sorted incident locks. No network/file I/O here. */
class EventTransactionStore(
    private val jdbc: JdbcTemplate,
    private val transaction: TransactionTemplate,
    private val revisions: RevisionReservations,
    private val mapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val terminal = setOf("CLOSED_EXIT", "CLOSED_MANUAL")

    fun ingest(input: CorrelatedDeviceMessage, validate: () -> EventValidationResult.Invalid?): EventIngestResult =
        revisions.withZone(input.zoneId) {
            retryTransaction { transaction.execute { ingestLocked(input, validate) }!! }
        }

    fun heartbeat(input: CorrelatedDeviceMessage): EventIngestResult = revisions.withZone(input.zoneId) {
        retryTransaction { transaction.execute { ingestLocked(input, { null }, heartbeat = true) }!! }
    }

    private fun ingestLocked(input: CorrelatedDeviceMessage, validate: () -> EventValidationResult.Invalid?, heartbeat: Boolean = false): EventIngestResult {
        // Reuse all ownership/policy/summary/pause checks; heartbeats never create reliable receipts or ACKs.
        fun reject(input: CorrelatedDeviceMessage, error: String, rule: String, now: Timestamp): EventIngestResult =
            if (heartbeat) noAck(rule) else this.reject(input, error, rule, now)
        val zones = jdbc.queryForList("SELECT * FROM zone_view WHERE zone_id=? FOR UPDATE", input.zoneId)
        if (zones.isEmpty() || zones.single()["device_id"] != input.deviceId) return noAck("device_registration")
        val zone = zones.single()
        val devices = jdbc.queryForList("SELECT * FROM device WHERE device_id=? FOR UPDATE", input.deviceId)
        if (devices.isEmpty() || devices.single()["zone_id"] != input.zoneId || devices.single()["enabled"] != true) {
            return noAck("device_registration")
        }
        val now = Timestamp.from(clock.instant())
        if (heartbeat && input.message["device_seq"].longValue() <= maxOf(
                (zone["last_device_seq"] as Number).toLong(), (zone["last_heartbeat_seq"] as Number).toLong())) return noAck("stale_heartbeat")
        val receipt = if (heartbeat) null else jdbc.queryForList(
            "SELECT * FROM inbound_receipt WHERE device_id=? AND message_id=?", input.deviceId, input.messageId,
        ).singleOrNull()
        if (receipt != null) {
            if (receipt["payload_sha256"].toString().trim() != input.canonicalHash || receipt["family"] != "events") {
                quarantine(input, "MESSAGE_ID_CONFLICT", "D04", now)
                return committed(input, "REJECTED", "MESSAGE_ID_CONFLICT")
            }
            return if (receipt["outcome"] == "STORED") committed(input, "DUPLICATE")
            else committed(input, "REJECTED", receipt["error_code"] as String)
        }
        val invalid = validate()
        if (invalid != null) {
            val version = input.message["schema_version"]
            val error = when {
                version != null && version.isNumber && version.decimalValue().stripTrailingZeros().scale() <= 0 &&
                    version.decimalValue().compareTo(2.toBigDecimal()) != 0 -> "UNSUPPORTED_SCHEMA"
                invalid.ruleId == "V11" || invalid.reason in setOf("event_identity", "image_event_identity") -> "EVENT_MISMATCH"
                else -> "INVALID_SCHEMA"
            }
            return reject(input, error, invalid.ruleId ?: invalid.reason, now)
        }
        val message = input.message
        // Image metadata has its own file/DB protocol. Do not discard its side effect then ACK STORED.
        if (message.path("event_type").asText() == "IMAGE_STATUS_CHANGED") return noAck("unsupported_image_status")
        val snapshot = message["snapshot"]
        val seq = message["device_seq"].longValue()
        val summaries = linkedMapOf<String, JsonNode>()
        val event = message.path("event").takeUnless { it.isMissingNode } ?: mapper.nullNode()
        if (!event.isNull) summaries[event["event_id"].asText()] = event
        if (!snapshot["current_event_id"].isNull) {
            summaries.putIfAbsent(snapshot["current_event_id"].asText(), summaryFrom(snapshot))
        }
        val versions = buildList {
            for (field in listOf("received_policy_version", "active_policy_version", "event_policy_version")) {
                if (!snapshot[field].isNull) add(snapshot[field].longValue())
            }
            if (!event.isNull) add(event["event_policy_version"].longValue())
        }.distinct()
        for (version in versions) {
            if (jdbc.queryForObject("SELECT count(*) FROM zone_policy WHERE zone_id=? AND version=?", Long::class.java, input.zoneId, version) != 1L) {
                return reject(input, "VERSION_CONFLICT", "D02", now)
            }
        }
        val existing = mutableMapOf<String, Map<String, Any>>()
        val tombstoned = mutableSetOf<String>()
        for ((id, summary) in summaries.toSortedMap()) {
            val rows = jdbc.queryForList("SELECT * FROM incident WHERE event_id=? FOR UPDATE", id)
            val old = rows.singleOrNull()
            if (old != null) {
                existing[id] = old
                if (old["device_id"] != input.deviceId || old["zone_id"] != input.zoneId) return reject(input, "EVENT_MISMATCH", "D03", now)
                if ((old["event_policy_version"] as Number).toLong() != summary["event_policy_version"].longValue()) {
                    return reject(input, "VERSION_CONFLICT", "D02", now)
                }
                if (seq > (old["last_device_seq"] as Number).toLong()) {
                    if (old["state"] in terminal && summary["state"].asText() != old["state"]) return reject(input, "INVALID_SCHEMA", "D06_closed", now)
                    if (summary["guidance_count"].intValue() < (old["guidance_count"] as Number).toInt()) return reject(input, "INVALID_SCHEMA", "D06_count", now)
                }
            }
            val tombstones = jdbc.queryForList("SELECT * FROM entity_tombstone WHERE entity_kind='EVENT' AND entity_id=?", id)
            if (tombstones.any { it["device_id"] != input.deviceId }) return reject(input, "EVENT_MISMATCH", "D03", now)
            if (tombstones.isNotEmpty()) {
                val deleted = tombstones.single()
                val finalSeq = (deleted["terminal_device_seq"] as? Number)?.toLong()
                if (finalSeq != null && seq > finalSeq && summary["state"].asText() != deleted["terminal_state"]) {
                    return reject(input, "INVALID_SCHEMA", "D06_tombstone", now)
                }
                tombstoned.add(id)
            }
        }
        // Validate pause ownership before any business write; global pause IDs cannot move devices.
        val freshSnapshot = seq > (zone["last_device_seq"] as Number).toLong() &&
            (snapshot["current_event_id"].isNull || snapshot["current_event_id"].asText() !in tombstoned)
        if (freshSnapshot) {
            for (pause in snapshot["pauses"]) {
                val old = jdbc.queryForList("SELECT device_id, zone_id FROM pause_projection WHERE pause_id=?", pause["pause_id"].asText()).singleOrNull()
                if (old != null && (old["device_id"] != input.deviceId || old["zone_id"] != input.zoneId)) {
                    return reject(input, "EVENT_MISMATCH", "pause_ownership", now)
                }
            }
        }
        if (!heartbeat) {
        insertReceipt(input, "STORED", null, now)
        jdbc.update("""
            INSERT INTO event_message(device_id,message_id,zone_id,event_id,device_seq,occurred_at,received_at,payload)
            VALUES (?,?,?,?,?,?,?,?::jsonb)
        """.trimIndent(), input.deviceId, input.messageId, input.zoneId, nullableText(message["event_id"]), seq,
            timestamp(message["occurred_at"]), now, mapper.writeValueAsString(message))
        }
        if (heartbeat && !snapshot["current_event_id"].isNull && snapshot["current_event_id"].asText() in tombstoned) return noAck("expired_heartbeat_event")
        var changed = false
        for ((id, summary) in summaries.toSortedMap()) {
            val old = existing[id]
            if (id in tombstoned || (old?.get("summary_expires_at") as? Timestamp)?.let { !it.after(now) } == true || (old != null && seq <= (old["last_device_seq"] as Number).toLong())) continue
            saveSummary(input, summary, old, seq, now)
            changed = true
        }
        if (freshSnapshot) {
            jdbc.update("""
                UPDATE zone_view SET snapshot=?::jsonb,current_event_id=?,last_device_seq=?,received_at=?,current_boot_id=?
                WHERE zone_id=?
            """.trimIndent(), mapper.writeValueAsString(snapshot), nullableText(snapshot["current_event_id"]), seq, now,
                snapshot["boot_id"].asText(), input.zoneId)
            updatePauses(input, snapshot, seq, now)
            changed = true
        }
        if (heartbeat) {
            jdbc.update("""UPDATE zone_view SET last_heartbeat_at=?,last_heartbeat_seq=?,connection_state='ONLINE',
                fps=?,pending_message_count=?,storage_ok=? WHERE zone_id=?""", now, seq,
                message["fps"].decimalValue(), message["pending_message_count"].longValue(), message["storage_ok"].booleanValue(), input.zoneId)
            changed = true
        }
        if (changed) incrementRevision(input.zoneId)
        return committed(input, "STORED")
    }

    private fun summaryFrom(snapshot: JsonNode): JsonNode = mapper.createObjectNode().apply {
        set<JsonNode>("event_id", snapshot["current_event_id"])
        set<JsonNode>("state", snapshot["incident_state"])
        for (field in listOf("event_policy_version", "guidance_count", "body_type", "use_type", "continuous_stop_ms", "overdue")) {
            set<JsonNode>(field, snapshot[field])
        }
        putNull("started_at"); putNull("closed_at")
        when (snapshot["incident_state"].asText()) {
            "CLOSED_EXIT" -> put("close_reason", "OBSERVED_EXIT")
            "CLOSED_MANUAL" -> put("close_reason", "MANUAL")
            else -> putNull("close_reason")
        }
    }

    private fun saveSummary(input: CorrelatedDeviceMessage, summary: JsonNode, old: Map<String, Any>?, seq: Long, now: Timestamp) {
        val state = summary["state"].asText()
        val closing = state in terminal
        val started = old?.get("started_at") as? Timestamp ?: timestamp(summary["started_at"])
        val closed = old?.get("closed_at") as? Timestamp ?: timestamp(summary["closed_at"])
        val firstClose = old?.get("first_close_received_at") as? Timestamp ?: if (closing) now else null
        val expiry = old?.get("summary_expires_at") as? Timestamp ?: if (closing) {
            Timestamp.from((closed ?: now).toInstant().plus(30, ChronoUnit.DAYS))
        } else null
        val affected = jdbc.update("""
            INSERT INTO incident(event_id,device_id,zone_id,state,event_policy_version,started_at,closed_at,close_reason,
                guidance_count,body_type,use_type,continuous_stop_ms,overdue,last_device_seq,first_received_at,first_close_received_at,summary_expires_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (event_id) DO UPDATE SET state=excluded.state, started_at=excluded.started_at,
                closed_at=excluded.closed_at,close_reason=excluded.close_reason,guidance_count=excluded.guidance_count,
                body_type=excluded.body_type,use_type=excluded.use_type,continuous_stop_ms=excluded.continuous_stop_ms,
                overdue=excluded.overdue,last_device_seq=excluded.last_device_seq,
                first_close_received_at=excluded.first_close_received_at,summary_expires_at=excluded.summary_expires_at
            WHERE incident.device_id=excluded.device_id AND incident.zone_id=excluded.zone_id
                AND incident.event_policy_version=excluded.event_policy_version AND incident.last_device_seq < excluded.last_device_seq
        """.trimIndent(), summary["event_id"].asText(), input.deviceId, input.zoneId, state, summary["event_policy_version"].longValue(),
            started, closed, nullableText(summary["close_reason"]), summary["guidance_count"].intValue(), summary["body_type"].asText(),
            summary["use_type"].asText(), summary["continuous_stop_ms"].longValue(), summary["overdue"].booleanValue(), seq,
            now, firstClose, expiry)
        check(affected == 1) { "concurrent_incident_conflict" }
    }

    private fun updatePauses(input: CorrelatedDeviceMessage, snapshot: JsonNode, seq: Long, now: Timestamp) {
        val present = snapshot["pauses"].map { it["pause_id"].asText() }.toSet()
        for (old in jdbc.queryForList("SELECT pause_id FROM pause_projection WHERE device_id=? AND status <> 'CLEARED'", input.deviceId)) {
            if (old["pause_id"] !in present) jdbc.update(
                "UPDATE pause_projection SET status='CLEARED',cleared_seen_at=?,last_device_seq=? WHERE pause_id=?", now, seq, old["pause_id"],
            )
        }
        for (pause in snapshot["pauses"]) {
            val affected = jdbc.update("""
                INSERT INTO pause_projection(pause_id,device_id,zone_id,target_event_id,source,status,payload,last_device_seq,first_seen_at)
                VALUES (?,?,?,?,?,?,?::jsonb,?,?)
                ON CONFLICT (pause_id) DO UPDATE SET target_event_id=excluded.target_event_id,source=excluded.source,
                    status=excluded.status,payload=excluded.payload,last_device_seq=excluded.last_device_seq,cleared_seen_at=NULL
                WHERE pause_projection.device_id=excluded.device_id AND pause_projection.zone_id=excluded.zone_id
                    AND pause_projection.last_device_seq < excluded.last_device_seq
            """.trimIndent(), pause["pause_id"].asText(), input.deviceId, input.zoneId, nullableText(pause["target_event_id"]),
                pause["source"].asText(), pause["status"].asText(), mapper.writeValueAsString(pause), seq, now)
            check(affected == 1) { "concurrent_pause_conflict" }
        }
    }

    private fun insertReceipt(input: CorrelatedDeviceMessage, outcome: String, error: String?, now: Timestamp) {
        jdbc.update("""
            INSERT INTO inbound_receipt(device_id,message_id,family,payload_sha256,outcome,error_code,first_received_at)
            VALUES (?,?,'events',?,?,?,?)
        """.trimIndent(), input.deviceId, input.messageId, input.canonicalHash, outcome, error, now)
    }

    private fun reject(input: CorrelatedDeviceMessage, error: String, rule: String, now: Timestamp): EventIngestResult.Committed {
        quarantine(input, error, rule, now)
        insertReceipt(input, "REJECTED", error, now)
        return committed(input, "REJECTED", error)
    }

    private fun quarantine(input: CorrelatedDeviceMessage, error: String, rule: String, now: Timestamp) {
        jdbc.update("""
            INSERT INTO inbound_quarantine(quarantine_id,device_id,message_id,topic,raw_payload,raw_sha256,canonical_sha256,
                error_code,rule_id,first_received_at,expires_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?::timestamptz + interval '30 days')
            ON CONFLICT(device_id,message_id,raw_sha256,rule_id) DO NOTHING
        """.trimIndent(), UUID.randomUUID().toString(), input.deviceId, input.messageId, input.topic, input.raw,
            EventCanonicalizer.sha256(input.raw), input.canonicalHash, error, rule, now, now)
    }

    private fun incrementRevision(zoneId: String) {
        check(jdbc.update("UPDATE zone_view SET view_revision=view_revision+1 WHERE zone_id=? AND view_revision < revision_range_end", zoneId) == 1) {
            "revision_range_exhausted"
        }
    }

    /** Server elapsed time only changes connectivity; never infer vehicle departure. */
    fun expireConnectivity() {
        for (id in jdbc.queryForList("SELECT zone_id FROM zone_view WHERE connection_state<>'OFFLINE' ORDER BY zone_id", String::class.java)) {
            revisions.withZone(id) {
                transaction.executeWithoutResult {
                    val zone = jdbc.queryForMap("SELECT * FROM zone_view WHERE zone_id=? FOR UPDATE", id)
                    jdbc.queryForMap("SELECT device_id FROM device WHERE device_id=? FOR UPDATE", zone["device_id"])
                    val last = (zone["last_heartbeat_at"] as? Timestamp)?.toInstant()
                    val age = last?.let { java.time.Duration.between(it, clock.instant()).toMillis() }
                    val state = when { age == null || age < 0 || age > 30_000 -> "OFFLINE"; age > 15_000 -> "STALE"; else -> "ONLINE" }
                    if (zone["connection_state"] != state) {
                        jdbc.update("UPDATE zone_view SET connection_state=? WHERE zone_id=?", state, id)
                        incrementRevision(id)
                    }
                }
            }
        }
    }

    /** Purge raw payloads, retain deduplication receipts, and tombstone expired incident summaries. */
    fun purgeExpired() {
        for (zoneId in jdbc.queryForList("SELECT zone_id FROM zone_view ORDER BY zone_id", String::class.java)) {
            revisions.withZone(zoneId) {
                retryTransaction {
                    transaction.executeWithoutResult {
                        val zone = jdbc.queryForMap("SELECT * FROM zone_view WHERE zone_id=? FOR UPDATE", zoneId)
                        val deviceId = zone["device_id"] as String
                        jdbc.queryForMap("SELECT device_id FROM device WHERE device_id=? FOR UPDATE", deviceId)
                        val now = Timestamp.from(clock.instant())
                        jdbc.update("DELETE FROM event_message WHERE zone_id=? AND received_at <= ?::timestamptz - interval '30 days'", zoneId, now)
                        jdbc.update("""
                            UPDATE inbound_quarantine SET raw_payload=NULL,payload_purged_at=?
                            WHERE device_id=? AND expires_at<=? AND raw_payload IS NOT NULL
                        """.trimIndent(), now, deviceId, now)
                        val expired = jdbc.queryForList("SELECT * FROM incident WHERE zone_id=? AND summary_expires_at<=? ORDER BY event_id FOR UPDATE", zoneId, now)
                        for (incident in expired) {
                            jdbc.update("""
                                INSERT INTO entity_tombstone(device_id,entity_kind,entity_id,terminal_state,terminal_device_seq,compacted_at)
                                VALUES (?,'EVENT',?,?,?,?) ON CONFLICT DO NOTHING
                            """.trimIndent(), deviceId, incident["event_id"], incident["state"], incident["last_device_seq"], now)
                            jdbc.update("DELETE FROM incident WHERE event_id=?", incident["event_id"])
                        }
                        if (expired.isNotEmpty()) incrementRevision(zoneId)
                    }
                }
            }
        }
    }

    private fun <T> retryTransaction(action: () -> T): T {
        var retries = 0
        while (true) {
            try { return action() } catch (error: DataAccessException) {
                val sql = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
                if (sql?.sqlState !in setOf("40P01", "40001", "55P03") || retries >= 3) throw error
                Thread.sleep((50L shl retries++) + ThreadLocalRandom.current().nextLong(51))
            }
        }
    }

    private fun timestamp(node: JsonNode): Timestamp? {
        if (node.isNull) return null
        // Wire strings remain intact in event_message; PostgreSQL stores microsecond precision.
        val text = node.asText().replace(Regex("\\.(\\d+)Z$")) { ".${it.groupValues[1].take(6)}Z" }
        return Timestamp.from(Instant.parse(text).truncatedTo(ChronoUnit.MICROS))
    }
    private fun nullableText(node: JsonNode): String? = if (node.isNull) null else node.asText()
    private fun committed(input: CorrelatedDeviceMessage, status: String, error: String? = null) =
        EventIngestResult.Committed(input.deviceId, input.messageId, status, error)
    private fun noAck(reason: String) = EventIngestResult.NotAcknowledged(reason)
}
