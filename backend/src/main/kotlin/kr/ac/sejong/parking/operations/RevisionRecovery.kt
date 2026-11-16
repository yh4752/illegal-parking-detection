package kr.ac.sejong.parking.operations

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kr.ac.sejong.parking.device.adapter.jdbc.*
import kr.ac.sejong.parking.device.application.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.time.Instant
import java.util.UUID

/** Offline single-zone operator. Uses the same OS lock as the running backend. No HTTP/MQTT starts here. */
class RevisionRecovery(private val jdbc: JdbcTemplate, private val tx: TransactionTemplate,
    private val mapper: ObjectMapper, private val primary: Path, private val copy: Path,
    private val ledger: RevisionLedger) {

    fun <T> exclusively(action: () -> T): T {
        require(primary.isAbsolute && copy.isAbsolute && primary.parent != copy.parent)
        Files.createDirectories(primary.parent); Files.createDirectories(copy.parent)
        require(primary.parent.toRealPath() != copy.parent.toRealPath())
        return FileChannel.open(primary.resolveSibling("revision-reservations.lock"), CREATE, WRITE).use { channel ->
            checkNotNull(channel.tryLock()) { "stop_backend_before_recovery" }.use { action() }
        }
    }

    private fun local(path: Path): ObjectNode? {
        if (!Files.exists(path)) return null
        check(Files.isRegularFile(path) && Files.size(path) <= 1_048_576)
        val value = mapper.readTree(Files.readAllBytes(path))
        check(value.isObject && value.path("format_version").asInt() == 1 && value.path("zones").isObject)
        for ((id, zone) in value["zones"].properties()) {
            check(zone.isObject && zone.path("zone_id").asText() == id && id.isNotBlank()) { "local_revision_zone_invalid" }
            for (field in listOf("reserved_through", "manifest_generation")) check(zone.path(field).isIntegralNumber &&
                zone[field].canConvertToLong() && zone[field].longValue() in 0..9_007_199_254_740_991L) { "local_revision_number_invalid" }
            if (zone.has("demo_initialization_claimed_at")) check(zone["demo_initialization_claimed_at"].isTextual &&
                zone["demo_initialization_claimed_at"].asText().isNotBlank()) { "local_initialization_claim_invalid" }
        }
        return value as ObjectNode
    }

    private fun pair(): ObjectNode {
        val first = checkNotNull(local(primary)) { "revision_file_missing" }
        check(first == local(copy)) { "revision_files_disagree" }
        return first
    }

    private fun write(path: Path, document: ObjectNode) {
        val temp = Files.createTempFile(path.parent, ".recovery-", ".tmp")
        try {
            FileChannel.open(temp, WRITE).use { file ->
                val data = ByteBuffer.wrap(mapper.writeValueAsBytes(document))
                while (data.hasRemaining()) file.write(data)
                file.force(true)
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(path.parent, READ).use { it.force(true) }
        } finally { Files.deleteIfExists(temp) }
    }

    private fun publish(head: RevisionHead, next: ObjectNode) {
        ledger.compareAndSet(head, next)
        write(primary, next)
        write(copy, next)
    }

    /** Snapshot boundaries fence even a backup from the currently active reserved range. */
    fun reserve(mode: String, reason: String): ObjectNode = exclusively {
        require(mode in setOf("prepare", "fence", "adopt") && reason.isNotBlank() && reason.length <= 200)
        val rows = jdbc.queryForList("SELECT * FROM zone_view ORDER BY zone_id")
        check(rows.size == 1) { "local_recovery_single_zone_only" }
        val head = ledger.head() // Missing/unavailable latest record never falls back to local files.
        val first = local(primary); val second = local(copy)
        if (mode == "fence") {
            check(pair() == head.document && rows.all { it["recovery_hold"] == false }) { "fence_requires_healthy_open_zone" }
            for (row in rows) check(head.document["zones"][row["zone_id"] as String]["reserved_through"].longValue() ==
                (row["revision_range_end"] as Number).toLong()) { "fence_database_mismatch" }
        }
        if (mode == "adopt") {
            check(head.version == 0L && head.document["zones"].isEmpty) { "adoption_requires_unused_independent_store" }
            check(first != null && first == second) { "adoption_requires_healthy_local_pair" }
            for (row in rows) check(first["zones"][row["zone_id"] as String]?.get("reserved_through")?.longValue() ==
                (row["revision_range_end"] as Number).toLong()) { "adoption_database_mismatch" }
        } else check(!head.document["zones"].isEmpty) { "independent_record_missing_no_recovery" }
        // Close first. A failure at any later stage leaves the DB closed or unable to start.
        if (mode != "fence") tx.executeWithoutResult { jdbc.update("UPDATE zone_view SET recovery_hold=true") }
        val next = head.document.deepCopy()
        val recoveryId = UUID.randomUUID().toString()
        for (row in rows) {
            val id = row["zone_id"] as String
            if (mode != "adopt") check(head.document["zones"].has(id)) { "independent_zone_record_missing" }
            val candidates = listOfNotNull(head.document["zones"][id], first?.get("zones")?.get(id), second?.get("zones")?.get(id))
            check(candidates.isNotEmpty()) { "unknown_zone_requires_review" }
            val maximum = (candidates.map { it["reserved_through"].longValue() } +
                listOf((row["revision_range_end"] as Number).toLong(), (row["view_revision"] as Number).toLong())).max()
            check(maximum in 0..9_007_199_253_740_991L) { "revision_space_exhausted" }
            val previous = candidates.first() as ObjectNode
            val zone = previous.deepCopy().put("zone_id", id)
                .put("reserved_through", maximum + 1_000_000)
                .put("manifest_generation", candidates.maxOf { it["manifest_generation"].longValue() } + 1)
                .put("reserved_at", Instant.now().toString()).put("recovery_id", recoveryId).put("recovery_mode", mode)
            // Preserve an initialization claim even when one local write was ahead at a crash.
            val claims = candidates.mapNotNull { it["demo_initialization_claimed_at"]?.asText() }.distinct()
            check(claims.size <= 1) { "initialization_claims_disagree" }
            claims.firstOrNull()?.let { zone.put("demo_initialization_claimed_at", it) }
            (next["zones"] as ObjectNode).set<ObjectNode>(id, zone)
        }
        publish(head, next) // independent CAS -> local primary -> local copy -> DB commit
        tx.executeWithoutResult {
            for (row in rows) {
                val id = row["zone_id"] as String
                val end = next["zones"][id]["reserved_through"].longValue()
                check(jdbc.update("""UPDATE zone_view SET revision_range_start=?,revision_range_end=?,view_revision=?,
                    recovery_hold=? WHERE zone_id=? AND revision_range_end=? AND view_revision=?""",
                    end - 999_999, end, end - 999_999, mode != "fence", id, row["revision_range_end"], row["view_revision"]) == 1) {
                    "database_changed_during_recovery"
                }
                if (mode != "fence") jdbc.update("""UPDATE zone_view SET connection_state='OFFLINE',last_heartbeat_at=NULL,
                    fps=NULL,pending_message_count=NULL,storage_ok=NULL WHERE zone_id=?""", id)
            }
            audit("REVISION_" + mode.uppercase(), recoveryId, reason)
        }
        report(if (mode == "fence") "FENCED" else "PREPARED", recoveryId)
    }

    /** Uses ordinary schema/policy/identity/order validation, but never publishes an ACK. */
    fun reconcile(payload: ByteArray): ObjectNode {
        val message = mapper.readTree(payload)
        val zoneId = message.path("zone_id").asText()
        val id = pair()["zones"][zoneId]?.get("recovery_id")?.asText() ?: error("prepare_recovery_first")
        RevisionReservations(jdbc, tx, mapper, primary, copy, ledger).use { reservations ->
            reservations.initialize()
            val store = EventTransactionStore(jdbc, tx, reservations, mapper, recoveryOnly = true)
            val service = EventIngestionService(EventMessageValidator(mapper) { _, _ -> true }, EventCanonicalizer(mapper), store)
            val result = service.receive("parking/v2/devices/${message.path("device_id").asText()}/events", payload)
            check(result is EventIngestResult.Committed && result.status == "STORED") { "device_reconciliation_rejected" }
            check(jdbc.queryForObject("SELECT last_device_seq FROM zone_view WHERE zone_id=?", Long::class.java, zoneId) ==
                message["device_seq"].longValue()) { "device_snapshot_not_applied" }
            tx.executeWithoutResult {
                val detail = mapper.createObjectNode().put("recovery_id", id)
                    .put("device_report_sha256", EventCanonicalizer.sha256(payload)).put("device_seq", message["device_seq"].longValue())
                jdbc.update("INSERT INTO audit_entry(audit_id,zone_id,device_id,action,detail,created_at) VALUES (?,?,?,'RECOVERY_RECONCILED',?::jsonb,now())",
                    UUID.randomUUID().toString(), zoneId, message["device_id"].asText(), mapper.writeValueAsString(detail))
            }
        }
        return exclusively {
            val result = report("RECONCILED", id)
            result.put("device_report_sha256", EventCanonicalizer.sha256(payload))
            result
        }
    }

    fun accept(report: ObjectNode, reason: String): ObjectNode = exclusively {
        require(reason.isNotBlank() && reason.length <= 200)
        check(report.path("stage").asText() == "RECONCILED" && report.path("device_report_sha256").asText().length == 64) {
            "reconciled_report_required"
        }
        check(jdbc.queryForObject("""SELECT count(*) FROM audit_entry WHERE action='RECOVERY_RECONCILED'
            AND detail->>'recovery_id'=? AND detail->>'device_report_sha256'=?""", Long::class.java,
            report["recovery_id"].asText(), report["device_report_sha256"].asText()) == 1L) { "persisted_reconciliation_required" }
        val current = report("RECONCILED", report["recovery_id"].asText())
        for (field in listOf("database_fingerprint", "ledger_version", "ledger_sha256", "zones"))
            check(report[field] == current[field]) { "recovery_report_stale" }
        check(current["zones"].all { it["recovery_hold"].booleanValue() }) { "recovery_already_open" }
        check(jdbc.queryForObject("SELECT (SELECT count(*) FROM command)+(SELECT count(*) FROM server_outbox)", Long::class.java) == 0L) {
            "pending_or_historical_commands_require_separate_review"
        }
        val head = ledger.requireDocument(pair())
        val next = head.document.deepCopy()
        for (zone in current["zones"]) {
            val id = zone["zone_id"].asText()
            check(next["zones"][id]["recovery_id"].asText() == report["recovery_id"].asText()) { "recovery_id_mismatch" }
            (next["zones"][id] as ObjectNode).put("recovery_accepted_report", EventCanonicalizer.sha256(mapper.writeValueAsBytes(report)))
        }
        publish(head, next)
        tx.executeWithoutResult {
            check(fingerprint() == report["database_fingerprint"].asText()) { "database_changed_before_acceptance" }
            for (zone in current["zones"]) check(jdbc.update("UPDATE zone_view SET recovery_hold=false WHERE zone_id=? AND recovery_hold",
                zone["zone_id"].asText()) == 1)
            audit("RECOVERY_ACCEPTED", report["recovery_id"].asText(), reason)
        }
        report("ACCEPTED", report["recovery_id"].asText())
    }

    private fun fingerprint(): String {
        val tables = jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname=current_schema() ORDER BY tablename", String::class.java)
        val summaries = tables.map { table ->
            check(table.matches(Regex("[a-z_]+")))
            table + ":" + jdbc.queryForObject("SELECT md5(coalesce(string_agg(row_to_json(t)::text,E'\\n' ORDER BY row_to_json(t)::text),'')) FROM \"$table\" t", String::class.java)
        }
        return EventCanonicalizer.sha256(summaries.joinToString("\n").toByteArray())
    }

    private fun report(stage: String, id: String): ObjectNode {
        val head = ledger.requireDocument(pair())
        return mapper.createObjectNode().put("stage", stage).put("scope", "LOCAL_ONLY").put("recovery_id", id)
            .put("created_at", Instant.now().toString()).put("ledger_version", head.version).put("ledger_sha256", head.sha256)
            .put("database_fingerprint", fingerprint()).apply {
                set<com.fasterxml.jackson.databind.JsonNode>("zones", mapper.valueToTree(jdbc.queryForList(
                    "SELECT zone_id,device_id,view_revision,revision_range_end,last_device_seq,recovery_hold FROM zone_view ORDER BY zone_id")))
                put("acked_events_after_backup_may_be_lost", true)
                put("automatic_commands_published", false)
            }.let { mapper.readTree(mapper.writeValueAsBytes(it)) as ObjectNode }
    }

    private fun audit(action: String, id: String, reason: String) {
        val detail = mapper.createObjectNode().put("recovery_id", id).put("reason", reason).put("scope", "LOCAL_ONLY")
        jdbc.update("INSERT INTO audit_entry(audit_id,action,detail,created_at) VALUES (?,?,?::jsonb,now())",
            UUID.randomUUID().toString(), action, mapper.writeValueAsString(detail))
    }
}
