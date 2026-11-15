package kr.ac.sejong.parking.device.adapter.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import java.time.Instant

/** Single-server gate. Manifest I/O is outside DB transactions; never guesses a lost high-water mark. */
class RevisionReservations(
    private val jdbc: JdbcTemplate,
    private val transaction: TransactionTemplate,
    private val mapper: ObjectMapper,
    private val primary: Path,
    private val backup: Path,
) : AutoCloseable {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var manifest: ObjectNode? = null
    private var failed = false

    @Synchronized
    fun initialize() {
        check(!TransactionSynchronizationManager.isActualTransactionActive())
        require(primary.isAbsolute && backup.isAbsolute && primary.parent != backup.parent)
        try {
            Files.createDirectories(primary.parent)
            Files.createDirectories(backup.parent)
            require(primary.parent.toRealPath() != backup.parent.toRealPath()) { "manifest_copy_must_be_separate" }
            channel = FileChannel.open(primary.resolveSibling("revision-reservations.lock"), CREATE, WRITE)
            lock = checkNotNull(channel!!.tryLock()) { "another_ingestion_process_is_running" }
            val hasPrimary = Files.exists(primary)
            val hasBackup = Files.exists(backup)
            check(hasPrimary == hasBackup) { "manifest_copy_missing" }
            manifest = if (hasPrimary) readPair() else {
                val used = jdbc.queryForObject(
                    "SELECT count(*) FROM zone_view WHERE revision_range_end <> 0 OR view_revision <> 0 OR snapshot IS NOT NULL",
                    Long::class.java,
                )!!
                check(used == 0L) { "manifest_missing_for_existing_state" }
                mapper.createObjectNode().apply { put("format_version", 1); putObject("zones") }
            }
            for (zone in jdbc.queryForList("SELECT zone_id FROM zone_view ORDER BY zone_id", String::class.java)) {
                ensureRange(zone)
            }
            if (!Files.exists(primary)) {
                val bytes = mapper.writeValueAsBytes(manifest)
                durableReplace(primary, bytes)
                durableReplace(backup, bytes)
            }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    @Synchronized
    fun <T> withZone(zoneId: String, action: () -> T): T {
        check(!failed && lock?.isValid == true) { "revision_gate_closed" }
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "file_io_inside_transaction" }
        try {
            check(readPair() == manifest) { "manifest_changed" }
            ensureRange(zoneId)
        } catch (error: Exception) {
            if (error !is ReservationReadUnavailable) failed = true
            throw error
        }
        return action()
    }

    /** A fresh-demo initialization is claimed outside the DB before clearing recovery_hold.
     * A restored pre-initialization DB cannot reuse this command with the latest manifest.
     * A crash after this claim needs operator recovery; never erase the claim to retry.
     */
    @Synchronized
    fun <T> withFreshInitializationClaim(zoneId: String, action: () -> T): T = withZone(zoneId) {
        val reserved = manifest!!["zones"][zoneId]
        check(reserved != null && reserved["reserved_through"].longValue() == 999_999L) { "initial_reservation_required" }
        check(!reserved.has("demo_initialization_claimed_at")) { "initialization_already_claimed_review_required" }
        val next = manifest!!.deepCopy()
        (next["zones"][zoneId] as ObjectNode).put("demo_initialization_claimed_at", Instant.now().toString())
        try {
            val bytes = mapper.writeValueAsBytes(next)
            durableReplace(primary, bytes)
            durableReplace(backup, bytes)
            manifest = mapper.readTree(bytes) as ObjectNode
        } catch (error: Exception) {
            failed = true
            throw error
        }
        action()
    }

    /** Read gate only; no DB/file writes and no I/O inside a read transaction. */
    @Synchronized
    fun checkReadable(zoneId: String, reservedThrough: Long) {
        check(!failed && lock?.isValid == true) { "revision_gate_closed" }
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "file_io_inside_transaction" }
        try {
            check(readPair() == manifest) { "manifest_changed" }
        } catch (error: Exception) { failed = true; throw error }
        val zone = manifest!!["zones"][zoneId]
        check(zone != null && zone["reserved_through"].longValue() == reservedThrough) { "read_reservation_mismatch" }
    }

    private fun ensureRange(zoneId: String) {
        // A failed preflight read has not touched the reservation files. Retry only this case.
        // Mismatches and any failure after durable reservation still latch the gate closed.
        val rows = try { jdbc.queryForList("SELECT * FROM zone_view WHERE zone_id=?", zoneId) }
            catch (error: org.springframework.dao.DataAccessException) { throw ReservationReadUnavailable(error) }
        if (rows.isEmpty()) return
        val row = rows.single()
        val oldEnd = (row["revision_range_end"] as Number).toLong()
        val current = (row["view_revision"] as Number).toLong()
        val zones = manifest!!["zones"] as ObjectNode
        val reserved = zones[zoneId]
        if (reserved != null) {
            check(reserved["zone_id"]?.asText() == zoneId && reserved["manifest_generation"].canConvertToLong())
            check(reserved["reserved_through"].canConvertToLong())
            check(reserved["reserved_through"].longValue() == oldEnd) { "manifest_db_range_mismatch" }
            if (current < oldEnd) return
        } else {
            check(oldEnd == 0L && current == 0L && row["snapshot"] == null) { "unreserved_existing_state" }
        }
        val start = if (reserved == null) 0L else oldEnd + 1
        val end = start + 999_999
        check(end <= 9_007_199_254_740_991L) { "revision_space_exhausted" }
        val next = manifest!!.deepCopy()
        (next["zones"] as ObjectNode).putObject(zoneId).apply {
            put("zone_id", zoneId)
            put("manifest_generation", (reserved?.get("manifest_generation")?.longValue() ?: 0L) + 1)
            put("reserved_through", end)
            put("reserved_at", Instant.now().toString())
            reserved?.get("demo_initialization_claimed_at")?.let { set<com.fasterxml.jackson.databind.JsonNode>("demo_initialization_claimed_at", it) }
        }
        // Either both copies are durable or the gate fails closed on this run/restart.
        val bytes = mapper.writeValueAsBytes(next)
        durableReplace(primary, bytes)
        durableReplace(backup, bytes)
        transaction.executeWithoutResult {
            val locked = jdbc.queryForMap("SELECT * FROM zone_view WHERE zone_id=? FOR UPDATE", zoneId)
            check((locked["revision_range_end"] as Number).toLong() == oldEnd)
            check((locked["view_revision"] as Number).toLong() == current)
            jdbc.update("""
                UPDATE zone_view SET revision_range_start=?, revision_range_end=?, view_revision=? WHERE zone_id=?
            """.trimIndent(), start, end, if (reserved == null) 0L else start, zoneId)
        }
        // Reparse the exact persisted bytes so IntNode/LongNode representation cannot
        // make an unchanged on-disk manifest look different on the next request.
        manifest = mapper.readTree(bytes) as ObjectNode
    }

    private fun readPair(): ObjectNode {
        check(Files.isRegularFile(primary) && Files.isRegularFile(backup)) { "manifest_missing" }
        check(Files.size(primary) <= 1_048_576 && Files.size(backup) <= 1_048_576)
        val first = mapper.readTree(Files.readAllBytes(primary))
        val second = mapper.readTree(Files.readAllBytes(backup))
        check(first == second && first.isObject && first["format_version"]?.asInt() == 1 && first["zones"]?.isObject == true) {
            "manifest_copies_disagree"
        }
        return first as ObjectNode
    }

    private fun durableReplace(path: Path, bytes: ByteArray) {
        val temp = Files.createTempFile(path.parent, ".revision-", ".tmp")
        try {
            FileChannel.open(temp, WRITE).use { file ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) file.write(buffer)
                file.force(true)
            }
            Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
            FileChannel.open(path.parent, READ).use { it.force(true) }
        } finally { Files.deleteIfExists(temp) }
    }

    @Synchronized
    override fun close() {
        lock?.release()
        channel?.close()
        lock = null
        channel = null
    }
}

private class ReservationReadUnavailable(cause: Exception): RuntimeException("reservation_database_unavailable", cause)
