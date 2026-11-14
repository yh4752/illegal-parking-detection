package kr.ac.sejong.parking.zone.application

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ac.sejong.parking.web.StrictJson
import kr.ac.sejong.parking.web.invalidRequest
import kr.ac.sejong.parking.web.validId
import org.springframework.util.MultiValueMap
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class EventFilter(val zone: String, val from: Instant?, val to: Instant?, val state: String?, val limit: Int, val cursor: String?) {
    companion object {
        private val states = setOf("OBSERVING", "STOP_CONFIRMED", "GUIDING", "GUIDED", "LONG_UNRESOLVED", "HELD", "CLOSED_EXIT", "CLOSED_MANUAL")
        fun parse(params: MultiValueMap<String, String>): EventFilter {
            if (params.keys.any { it !in setOf("zone_id", "from", "to", "state", "limit", "cursor") } || params.values.any { it.size != 1 }) invalidRequest()
            val zone = validId(params.getFirst("zone_id") ?: invalidRequest())
            val from = params.getFirst("from")?.let(::utc); val to = params.getFirst("to")?.let(::utc)
            if (from != null && to != null && from >= to) invalidRequest()
            val state = params.getFirst("state")?.also { if (it !in states) invalidRequest() }
            val limit = params.getFirst("limit")?.let { if (!it.matches(Regex("[0-9]{1,3}"))) invalidRequest(); it.toInt() } ?: 20
            if (limit !in 1..100) invalidRequest()
            val cursor = params.getFirst("cursor")?.also { if (it.length !in 1..2048) invalidRequest() }
            return EventFilter(zone, from, to, state, limit, cursor)
        }
        fun utc(value: String): Instant {
            if (!value.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z"))) invalidRequest()
            return try { Instant.parse(value) } catch (_: java.time.DateTimeException) { invalidRequest() }
        }
    }
}
data class CursorPosition(val receivedAt: Instant, val eventId: String)
class EventCursor(private val mapper: ObjectMapper, private val json: StrictJson, key: ByteArray) {
    private val secret = key.copyOf().also { require(it.size >= 32) { "cursor_key_requires_at_least_32_random_bytes" } }
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private fun signature(data: ByteArray) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }.doFinal(data)
    private fun fingerprint(filter: EventFilter) = encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(
        mapper.writeValueAsBytes(listOf(filter.zone, filter.from?.toString(), filter.to?.toString(), filter.state))))
    fun encode(filter: EventFilter, position: CursorPosition): String {
        val data = mapper.writeValueAsBytes(mapOf("v" to 1, "f" to fingerprint(filter), "at" to position.receivedAt.toString(), "id" to position.eventId))
        return encoder.encodeToString(data) + "." + encoder.encodeToString(signature(data))
    }
    fun decode(filter: EventFilter): CursorPosition? {
        val cursor = filter.cursor ?: return null
        try {
            if (cursor.length !in 1..2048) invalidRequest()
            val parts = cursor.split('.'); if (parts.size != 2) invalidRequest()
            val data = decoder.decode(parts[0]); val signed = decoder.decode(parts[1])
            if (!MessageDigest.isEqual(signature(data), signed)) invalidRequest()
            val value = json.parse(data)
            if (!value.isObject || value.fieldNames().asSequence().toSet() != setOf("v", "f", "at", "id") ||
                !value["v"].isIntegralNumber || !value["v"].canConvertToLong() || value["v"].longValue() != 1L ||
                !value["f"].isTextual || value["f"].textValue() != fingerprint(filter) ||
                !value["at"].isTextual || !value["id"].isTextual) invalidRequest()
            return CursorPosition(EventFilter.utc(value["at"].textValue()), validId(value["id"].textValue()))
        } catch (_: IllegalArgumentException) { invalidRequest() }
    }
}
