package kr.ac.sejong.parking.device.application

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

sealed interface EventValidationResult {
    data class Validated(val message: JsonNode) : EventValidationResult
    // Internal diagnostic, not an application ACK or a wire error code.
    data class Invalid(val reason: String, val ruleId: String? = null) : EventValidationResult
}

sealed interface EventParseResult {
    data class Parsed(val message: JsonNode, val topicDevice: String) : EventParseResult
    data class Invalid(val reason: String) : EventParseResult
}

fun interface DeviceRegistrationLookup {
    fun isEnabled(deviceId: String, zoneId: String): Boolean
}

/** Stateless contract checks and current registration lookup; no persistence or ACK. */
class EventMessageValidator(
    objectMapper: ObjectMapper,
    private val registration: DeviceRegistrationLookup,
) {
    private val mapper = objectMapper.copy()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    private val schema = run {
        val resource = requireNotNull(javaClass.getResourceAsStream("/contracts/parking.schema.json"))
        val canonical = resource.use { mapper.readTree(it) }
        val eventSchema = mapper.createObjectNode().apply {
            put("\$schema", canonical["\$schema"].asText())
            put("\$ref", "#/\$defs/EventMessage")
            set<JsonNode>("\$defs", canonical["\$defs"])
        }
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
            eventSchema, SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build(),
        )
    }
    private val eventTopic = Regex("^parking/v2/devices/([^/+#\\u0000]+)/events$")

    fun parse(topic: String, payload: ByteArray): EventParseResult {
        if (payload.size > 65_536) return EventParseResult.Invalid("payload_too_large")
        val topicDevice = eventTopic.matchEntire(topic)?.groupValues?.get(1)
            ?: return EventParseResult.Invalid("topic")
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload)).toString()
        } catch (_: CharacterCodingException) {
            return EventParseResult.Invalid("utf8")
        }
        val message: JsonNode = try {
            mapper.readTree(text) ?: return EventParseResult.Invalid("json")
        } catch (_: JsonProcessingException) {
            return EventParseResult.Invalid("json")
        }
        return EventParseResult.Parsed(message, topicDevice)
    }

    fun validate(topic: String, payload: ByteArray): EventValidationResult {
        val parsed = when (val input = parse(topic, payload)) {
            is EventParseResult.Invalid -> return invalid(input.reason)
            is EventParseResult.Parsed -> input
        }
        validateContent(parsed.message)?.let { return it }
        val message = parsed.message
        if (parsed.topicDevice != message["device_id"].asText()) return invalid("topic_device_mismatch")
        if (!registration.isEnabled(message["device_id"].asText(), message["zone_id"].asText())) {
            return invalid("device_registration")
        }
        return EventValidationResult.Validated(message)
    }

    /** No registration lookup. Ingestion calls this after replaying any committed receipt. */
    fun validateContent(message: JsonNode): EventValidationResult.Invalid? {
        if (schema.validate(message).isNotEmpty()) return invalid("schema")

        val snapshot = message["snapshot"]
        // JSON Schema integers include 10.0; compare numeric values, not Jackson node classes.
        if (message["device_seq"].decimalValue().compareTo(snapshot["device_seq"].decimalValue()) != 0) {
            return invalid("snapshot_device_seq")
        }
        for (field in listOf("boot_id", "clock_synced")) {
            if (message[field] != snapshot[field]) return invalid("snapshot_$field")
        }
        val event = message["event"]
        if (!event.isNull && message["event_id"] != event["event_id"]) return invalid("event_identity")
        val image = message["image_update"]
        if (!image.isNull && message["event_id"] != image["event_id"]) return invalid("image_event_identity")
        // Historical event identity is intentionally NOT forced to match snapshot.current_event_id.
        return EventMessageSemantics.validate(message)
    }

    private fun invalid(reason: String) = EventValidationResult.Invalid(reason)
}
