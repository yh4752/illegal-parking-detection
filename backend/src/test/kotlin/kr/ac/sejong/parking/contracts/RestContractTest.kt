package kr.ac.sejong.parking.contracts

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RestContractTest {
    private val mapper = ObjectMapper()
    private fun document(path: String) = requireNotNull(javaClass.getResourceAsStream(path)).use { mapper.readTree(it) }
    private val source = document("/contracts/parking.schema.json")
    private val effective = document("/contracts/effective/parking.schema.json")
    private fun schema(definition: String, root: JsonNode = effective) = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(mapper.createObjectNode().apply {
            put("\$schema", root["\$schema"].asText()); put("\$ref", "#/\$defs/$definition")
            set<JsonNode>("\$defs", root["\$defs"])
        }, SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build())

    @Test fun `REST 일반 오류만 늘리고 MQTT와 성공 DTO를 바꾸지 않는다`() {
        for ((name, original) in source["\$defs"].properties()) {
            if (name != "RestError") assertThat(effective["\$defs"][name]).describedAs(name).isEqualTo(original)
        }
        for (code in listOf("RATE_LIMITED", "SERVICE_UNAVAILABLE", "INTERNAL_ERROR")) {
            val error = mapper.readTree("""{"code":"$code","message":"일시 오류","details":{},"retryable":true,"request_id":"r-1"}""")
            assertThat(schema("RestError").validate(error)).isEmpty()
            assertThat(schema("RestError", source).validate(error)).isNotEmpty()
            assertThat(schema("error_code").validate(mapper.valueToTree(code))).isNotEmpty()
        }
        assertThat(schema("RestError").validate(mapper.readTree("""{"code":"INTERNAL_ERROR"}"""))).isNotEmpty()
        assertThat(schema("RestError").validate(mapper.readTree("""{"code":"TYPO","message":"x","details":{},"retryable":false,"request_id":"r"}"""))).isNotEmpty()
    }

    @Test fun `새 오류가 응용 ACK에 들어가면 거부된다`() {
        val ack = mapper.readTree("""{"schema_version":2,"device_id":"edge-01","message_id":"m1","status":"REJECTED","error_code":"SERVICE_UNAVAILABLE","committed_at":"2026-11-14T00:00:00Z"}""")
        assertThat(schema("Ack", source).validate(ack)).isNotEmpty()
        assertThat(schema("Ack", effective).validate(ack)).isNotEmpty()
    }
}
