package kr.ac.sejong.parking.zone.adapter.jdbc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kr.ac.sejong.parking.web.ApiProblem
import kr.ac.sejong.parking.zone.application.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

data class ReadResult(val zoneId: String, val reservedThrough: Long, val body: JsonNode)
class JdbcReadStore(jdbc: JdbcTemplate, manager: PlatformTransactionManager, private val mapper: ObjectMapper, private val cursor: EventCursor) {
    private val sql = NamedParameterJdbcTemplate(jdbc)
    private val consistent = TransactionTemplate(manager).apply {
        isReadOnly = true; isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
    }
    private val accessSql = """
        EXISTS(SELECT 1 FROM user_zone_access a JOIN app_user u ON u.user_id=a.user_id
            WHERE a.zone_id=z.zone_id AND a.user_id=:user AND u.enabled AND u.role IN ('VIEWER','OPERATOR')) AS permitted
    """.trimIndent()
    private fun utc(column: String) = "to_char($column AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')"
    private val imagesSql = """
        SELECT COALESCE(jsonb_agg(image_ref ORDER BY first_received_at DESC,image_id DESC),'[]'::jsonb) FROM (
          SELECT d.first_received_at,d.image_id,jsonb_build_object(
            'image_id',d.image_id,'image_kind',d.image_kind,'event_id',d.event_id,
            'captured_at',${utc("d.captured_at")},'received_at',${utc("d.first_received_at")},'expires_at',${utc("d.expires_at")},
            'status',CASE WHEN d.expires_at<=:now THEN 'EXPIRED' ELSE d.status END,
            'error_code',CASE WHEN d.expires_at<=:now THEN 'RETENTION_EXPIRED' ELSE d.error_code END) AS image_ref
          FROM device_image d WHERE %s ORDER BY d.first_received_at DESC,d.image_id DESC %s
        ) refs
    """.trimIndent()
    private val commandsSql = """
        SELECT COALESCE(jsonb_agg(command_view ORDER BY issued_at DESC,command_id DESC),'[]'::jsonb) FROM (
          SELECT c.issued_at,c.command_id,jsonb_build_object(
            'command_id',c.command_id,'type',c.type,'server_status',c.server_status,
            'issued_at',${utc("c.issued_at")},'expires_at',${utc("c.expires_at")},
            'result_received_at',${utc("c.result_received_at")},'result',c.result,'error_code',c.error_code,
            'result_data',jsonb_build_object('pause_ids',COALESCE(c.result_data->'pause_ids','[]'::jsonb),
              'image_id',c.result_data->'image_id','confirmation_token',NULL,
              'token_valid_for_ms',c.result_data->'token_valid_for_ms','closed_event_id',c.result_data->'closed_event_id')) AS command_view
          FROM command c WHERE c.zone_id=z.zone_id AND (c.first_terminal_at IS NULL OR c.first_terminal_at>:cutoff)
          ORDER BY c.issued_at DESC,c.command_id DESC LIMIT 20
        ) commands
    """.trimIndent()
    private fun parameters(user: String, zone: String, now: Instant) = mutableMapOf<String, Any?>(
        "user" to user, "zone" to zone, "now" to Timestamp.from(now), "cutoff" to Timestamp.from(now.minus(Duration.ofDays(30))),
    )
    private fun assertAccess(r: ResultSet): Long {
        if (!r.getBoolean("permitted")) throw ApiProblem(403, "FORBIDDEN", "이 구역의 접근 권한이 없습니다.")
        if (r.getBoolean("recovery_hold")) throw ApiProblem(503, "SERVICE_UNAVAILABLE", "구역 상태 확인이 필요합니다.")
        return r.getLong("revision_range_end")
    }
    private fun zoneAccess(params: Map<String, Any?>): Long = sql.query(
        "SELECT z.*, $accessSql FROM zone_view z WHERE z.zone_id=:zone", params,
        { r, _ -> assertAccess(r) },
    ).singleOrNull() ?: throw ApiProblem(404, "NOT_FOUND", "구역을 찾을 수 없습니다.")

    /** One statement freezes permission, hold, revision, snapshot and both collections together. */
    fun zone(user: String, zone: String, now: Instant): ReadResult = sql.query("""
        SELECT z.*, $accessSql, ($commandsSql) AS commands,
          (${imagesSql.format("d.zone_id=z.zone_id", "LIMIT 10")}) AS images
        FROM zone_view z WHERE z.zone_id=:zone
    """.trimIndent(), parameters(user, zone, now), { r, _ ->
        val range = assertAccess(r)
        ReadResult(zone, range, mapper.createObjectNode().apply {
            put("zone_id", zone); put("view_revision", r.getLong("view_revision"))
            put("received_at", r.getTimestamp("received_at")?.toInstant()?.toString())
            put("last_heartbeat_at", r.getTimestamp("last_heartbeat_at")?.toInstant()?.toString())
            put("connection_state", r.getString("connection_state"))
            set<JsonNode>("snapshot", r.getString("snapshot")?.let(mapper::readTree) ?: mapper.nullNode())
            set<JsonNode>("commands", mapper.readTree(r.getString("commands")))
            set<JsonNode>("image_refs", mapper.readTree(r.getString("images")))
        })
    }).singleOrNull() ?: throw ApiProblem(404, "NOT_FOUND", "구역을 찾을 수 없습니다.")

    fun events(user: String, filter: EventFilter, now: Instant): ReadResult = consistent.execute {
        val params = parameters(user, filter.zone, now)
        val range = zoneAccess(params)
        val position = cursor.decode(filter)
        val where = mutableListOf("i.zone_id=:zone", "(i.summary_expires_at IS NULL OR i.summary_expires_at>:now)")
        filter.from?.let { where += "i.first_received_at>=:from"; params["from"] = Timestamp.from(it) }
        filter.to?.let { where += "i.first_received_at<:to"; params["to"] = Timestamp.from(it) }
        filter.state?.let { where += "i.state=:state"; params["state"] = it }
        position?.let { where += "(i.first_received_at,i.event_id)<(:after_time,:after_id)"; params["after_time"] = Timestamp.from(it.receivedAt); params["after_id"] = it.eventId }
        params["limit"] = filter.limit + 1
        val records = sql.query("SELECT i.* FROM incident i WHERE ${where.joinToString(" AND ")} ORDER BY i.first_received_at DESC,i.event_id DESC LIMIT :limit", params,
            { r, _ -> Pair(event(r).apply { put("manual_use_type", r.getString("manual_use_type")) }, CursorPosition(r.getTimestamp("first_received_at").toInstant(), r.getString("event_id"))) })
        val page = records.take(filter.limit)
        ReadResult(filter.zone, range, mapper.createObjectNode().apply {
            set<JsonNode>("items", mapper.createArrayNode().also { array -> page.forEach { array.add(it.first) } })
            put("next_cursor", if (records.size > filter.limit) cursor.encode(filter, page.last().second) else null)
        })
    }!!

    fun detail(user: String, id: String, now: Instant): ReadResult = consistent.execute {
        val lookup = mapOf("id" to id)
        val owner = sql.query("SELECT zone_id FROM incident WHERE event_id=:id", lookup, { r, _ -> r.getString(1) }).singleOrNull()
        val zone = owner ?: sql.query("""SELECT d.zone_id FROM entity_tombstone t JOIN device d ON d.device_id=t.device_id
            WHERE t.entity_kind='EVENT' AND t.entity_id=:id ORDER BY d.zone_id LIMIT 1""", lookup, { r, _ -> r.getString(1) }).singleOrNull()
            ?: throw ApiProblem(404, "NOT_FOUND", "사건을 찾을 수 없습니다.")
        val params = parameters(user, zone, now).apply { put("id", id) }
        val range = zoneAccess(params)
        if (owner == null) throw ApiProblem(410, "GONE", "보관기한이 지난 사건입니다.")
        val body = sql.query("SELECT * FROM incident WHERE event_id=:id", params, { r, _ ->
            if (r.getTimestamp("summary_expires_at")?.toInstant()?.let { !it.isAfter(now) } == true)
                throw ApiProblem(410, "GONE", "보관기한이 지난 사건입니다.")
            mapper.createObjectNode().apply {
                set<JsonNode>("event", event(r)); put("manual_use_type", r.getString("manual_use_type")); put("last_device_seq", r.getLong("last_device_seq"))
            }
        }).single()
        val messages = sql.query("""SELECT payload FROM event_message WHERE event_id=:id AND zone_id=:zone AND received_at>:cutoff
            ORDER BY device_seq,received_at,message_id""", params, { r, _ -> mapper.readTree(r.getString(1)) })
        val reviews = sql.query("SELECT * FROM review WHERE event_id=:id AND zone_id=:zone AND created_at>:cutoff ORDER BY created_at,review_id", params, { r, _ ->
            mapper.createObjectNode().apply {
                for (key in listOf("review_id", "event_id", "created_by", "note", "use_type")) put(key, r.getString(key))
                put("created_at", r.getTimestamp("created_at").toInstant().toString())
            }
        })
        body.set<JsonNode>("messages", mapper.valueToTree(messages)); body.set<JsonNode>("reviews", mapper.valueToTree(reviews))
        body.set<JsonNode>("images", sql.query(imagesSql.format("d.zone_id=:zone AND d.event_id=:id", ""), params, { r, _ -> mapper.readTree(r.getString(1)) }).single())
        ReadResult(zone, range, body)
    }!!

    private fun event(r: ResultSet): ObjectNode = mapper.createObjectNode().apply {
        for (key in listOf("event_id", "state", "close_reason", "body_type", "use_type")) put(key, r.getString(key))
        for (key in listOf("event_policy_version", "guidance_count", "continuous_stop_ms")) put(key, r.getLong(key))
        for (key in listOf("started_at", "closed_at")) put(key, r.getTimestamp(key)?.toInstant()?.toString())
        put("overdue", r.getBoolean("overdue"))
    }
}
