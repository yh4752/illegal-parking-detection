package kr.ac.sejong.parking.device.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ac.sejong.parking.device.adapter.jdbc.EventTransactionStore
import org.erdtman.jcs.JsonCanonicalizer
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock

data class CorrelatedEvent(
    val deviceId: String, val zoneId: String, val messageId: String,
    val topic: String, val raw: ByteArray, val message: JsonNode, val canonicalHash: String,
)

sealed interface EventIngestResult {
    data class Committed(val deviceId: String, val messageId: String, val status: String, val errorCode: String?) : EventIngestResult
    data class NotAcknowledged(val reason: String) : EventIngestResult
}

/** Identity is RFC8785, not JSONB rendering or plain object-key sorting. */
class EventCanonicalizer(private val mapper: ObjectMapper) {
    fun hash(message: JsonNode): String {
        requireValidUnicode(message)
        val canonical = JsonCanonicalizer(mapper.writeValueAsString(message)).encodedUTF8
        // Prevent a non-representable input from rounding onto an already accepted integer.
        require(sameNumbers(message, mapper.readTree(canonical))) { "non_representable_number" }
        return sha256(canonical)
    }

    private fun sameNumbers(left: JsonNode, right: JsonNode): Boolean = when {
        left.isNumber -> right.isNumber && left.decimalValue().compareTo(right.decimalValue()) == 0
        left.isObject -> left.properties().all { (key, value) -> sameNumbers(value, right[key]) }
        left.isArray -> (0 until left.size()).all { sameNumbers(left[it], right[it]) }
        else -> true
    }

    private fun requireValidUnicode(node: JsonNode) {
        fun check(text: String) {
            var index = 0
            while (index < text.length) {
                val char = text[index++]
                require(!char.isLowSurrogate()) { "unpaired_surrogate" }
                if (char.isHighSurrogate()) {
                    require(index < text.length && text[index++].isLowSurrogate()) { "unpaired_surrogate" }
                }
            }
        }
        if (node.isTextual) check(node.asText())
        if (node.isObject) node.properties().forEach { (key, value) -> check(key); requireValidUnicode(value) }
        if (node.isArray) node.forEach { requireValidUnicode(it) }
    }

    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}

class EventIngestionService(
    private val validator: EventMessageValidator,
    private val canonicalizer: EventCanonicalizer,
    private val store: EventTransactionStore,
) {
    fun receive(topic: String, payload: ByteArray): EventIngestResult {
        val parsed = when (val result = validator.parse(topic, payload)) {
            is EventParseResult.Invalid -> return EventIngestResult.NotAcknowledged(result.reason)
            is EventParseResult.Parsed -> result
        }
        val message = parsed.message
        fun id(field: String): String? = message[field]?.takeIf { it.isTextual }?.asText()?.takeIf {
            it.codePointCount(0, it.length) in 1..128 && '\u0000' !in it
        }
        val deviceId = id("device_id") ?: return EventIngestResult.NotAcknowledged("uncorrelated_envelope")
        val zoneId = id("zone_id") ?: return EventIngestResult.NotAcknowledged("uncorrelated_envelope")
        val messageId = id("message_id") ?: return EventIngestResult.NotAcknowledged("uncorrelated_envelope")
        if (deviceId != parsed.topicDevice) return EventIngestResult.NotAcknowledged("topic_device_mismatch")
        val hash = try {
            canonicalizer.hash(message)
        } catch (_: IOException) {
            return EventIngestResult.NotAcknowledged("canonical_json")
        } catch (_: IllegalArgumentException) {
            return EventIngestResult.NotAcknowledged("canonical_json")
        }
        return store.ingest(CorrelatedEvent(deviceId, zoneId, messageId, topic, payload, message, hash)) {
            validator.validateContent(message)
        }
    }
}

/** Construct only after the transaction returned: committed_at is a server UTC timestamp. */
class EventAckFactory(private val mapper: ObjectMapper, private val clock: Clock = Clock.systemUTC()) {
    fun create(result: EventIngestResult.Committed): ByteArray = mapper.writeValueAsBytes(mapper.createObjectNode().apply {
        put("schema_version", 2)
        put("device_id", result.deviceId)
        put("message_id", result.messageId)
        put("status", result.status)
        if (result.errorCode == null) putNull("error_code") else put("error_code", result.errorCode)
        put("committed_at", clock.instant().toString())
    })
}
