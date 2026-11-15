package kr.ac.sejong.parking.device

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kr.ac.sejong.parking.device.application.DeviceRegistrationLookup
import kr.ac.sejong.parking.device.application.EventMessageValidator
import kr.ac.sejong.parking.device.application.EventValidationResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class EventMessageValidatorTest {
    private val mapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    private val examples = javaClass.getResourceAsStream("/contracts/examples.json")!!.use { mapper.readTree(it) }
    private val validator = EventMessageValidator(mapper) { device, zone -> device == "edge-01" && zone == "zone-01" }
    private val topic = "parking/v2/devices/edge-01/events"

    private fun example(name: String = "guidance_complete") = examples[name].deepCopy<ObjectNode>()
    private fun reason(value: JsonNode, topic: String = this.topic): String? =
        (validator.validate(topic, mapper.writeValueAsBytes(value)) as? EventValidationResult.Invalid)?.reason

    @ParameterizedTest
    @ValueSource(strings = ["guidance_complete", "manual_close_event", "automatic_photo_available"])
    fun `v1_43의 사건 예제 원문을 허용한다`(name: String) {
        val input = example(name)
        val result = validator.validate(topic, mapper.writeValueAsBytes(input))
        assertThat(result).isInstanceOf(EventValidationResult.Validated::class.java)
        assertThat((result as EventValidationResult.Validated).message).isEqualTo(input)
    }

    @ParameterizedTest
    @ValueSource(strings = ["device_id", "event_id", "image_update", "snapshot", "occurred_at"])
    fun `null 허용 필드도 키를 생략하면 거부한다`(field: String) {
        assertThat(reason(example().apply { remove(field) })).isEqualTo("schema")
    }

    @ParameterizedTest
    @ValueSource(strings = ["-1", "9007199254740992", "9007199254740991.1", "true", "null", "\"10\""])
    fun `잘못된 순번 타입과 범위를 거부한다`(sequence: String) {
        val value = example().apply { set<JsonNode>("device_seq", mapper.readTree(sequence)) }
        assertThat(reason(value)).isEqualTo("schema")
    }

    @Test
    fun `JSON Schema의 정수는 소수 표기라도 수학적으로 정수이면 허용한다`() {
        val value = example().apply { put("device_seq", 10.0) }
        assertThat(reason(value)).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["{", "", "[]", "{} {}", "{\"x\":NaN}", "{\"x\":Infinity}"])
    fun `유효하지 않은 JSON이나 메시지 모양을 거부한다`(text: String) {
        assertThat(validator.validate(topic, text.toByteArray())).isInstanceOf(EventValidationResult.Invalid::class.java)
    }

    @Test
    fun `중복 키와 UTF8이 아닌 바이트와 크기 초과를 거부한다`() {
        val duplicate = mapper.writeValueAsString(example()).replaceFirst("{", "{\"device_id\":\"edge-01\",")
        assertThat(validator.validate(topic, duplicate.toByteArray())).isEqualTo(EventValidationResult.Invalid("json"))
        assertThat(validator.validate(topic, byteArrayOf(0xc3.toByte(), 0x28))).isEqualTo(EventValidationResult.Invalid("utf8"))
        assertThat(validator.validate(topic, ByteArray(65_537))).isEqualTo(EventValidationResult.Invalid("payload_too_large"))
    }

    @Test
    fun `64KiB 경계는 허용하고 넘으면 거부한다`() {
        val json = mapper.writeValueAsBytes(example())
        val padded = json + ByteArray(65_536 - json.size) { 32 }
        assertThat(validator.validate(topic, padded)).isInstanceOf(EventValidationResult.Validated::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["2026-02-30T00:00:00Z", "2026-11-03T12:00:00+09:00"])
    fun `실재하지 않는 날짜와 UTC Z가 아닌 시각을 거부한다`(timestamp: String) {
        assertThat(reason(example().apply { put("occurred_at", timestamp) })).isEqualTo("schema")
    }

    @Test
    fun `알 수 없는 필드와 지원하지 않는 버전을 거부한다`() {
        assertThat(reason(example().apply { put("extra", true) })).isEqualTo("schema")
        assertThat(reason(example().apply { put("schema_version", 1) })).isEqualTo("schema")
        assertThat(reason(example().apply { (get("snapshot") as ObjectNode).put("extra", true) })).isEqualTo("schema")
    }

    @Test
    fun `토픽 장치와 본문 장치 및 DB 등록을 구분한다`() {
        assertThat(reason(example(), "learning/hello")).isEqualTo("topic")
        assertThat(reason(example(), "parking/v2/devices/edge-02/events")).isEqualTo("topic_device_mismatch")
        assertThat(reason(example().apply { put("zone_id", "zone-02") })).isEqualTo("device_registration")
        val unregistered = example().apply { put("device_id", "edge-02") }
        assertThat(reason(unregistered, "parking/v2/devices/edge-02/events")).isEqualTo("device_registration")
    }

    @Test
    fun `envelope와 snapshot의 순번 및 부팅 ID를 비교한다`() {
        assertThat(reason(example().apply { (get("snapshot") as ObjectNode).put("device_seq", 11) }))
            .isEqualTo("snapshot_device_seq")
        assertThat(reason(example().apply { (get("snapshot") as ObjectNode).put("boot_id", "other") }))
            .isEqualTo("snapshot_boot_id")
        // Each object's schema is valid, but the two clock flags disagree.
        assertThat(reason(example().apply { put("clock_synced", false); putNull("occurred_at") }))
            .isEqualTo("snapshot_clock_synced")
    }

    @Test
    fun `사건과 사진의 참조 ID를 검사하되 현재 사건과 과거 사건을 강제로 같게 하지 않는다`() {
        assertThat(reason(example().apply { put("event_id", "other") })).isEqualTo("event_identity")
        assertThat(reason(example("automatic_photo_available").apply { put("event_id", "other") }))
            .isEqualTo("image_event_identity")
        assertThat(reason(example().apply { (get("snapshot") as ObjectNode).put("current_event_id", "new-event") })).isNull()
    }

    @Test
    fun `사건 없음과 시각 불신 및 종료 이유 조합을 검사한다`() {
        assertThat(reason(example().apply { (get("snapshot") as ObjectNode).putNull("current_event_id") })).isEqualTo("schema")
        assertThat(reason(example().apply { ((get("snapshot"))["clock_status"] as ObjectNode).put("state", "UNTRUSTED") })).isEqualTo("schema")
        assertThat(reason(example("manual_close_event").apply { (get("event") as ObjectNode).putNull("close_reason") })).isEqualTo("schema")
    }

    @Test
    fun `계약 위반 메시지는 DB 조회 전에 거부한다`() {
        var calls = 0
        val checker = EventMessageValidator(mapper, DeviceRegistrationLookup { _, _ -> calls++; true })
        checker.validate(topic, "{}".toByteArray())
        assertThat(calls).isZero()
    }
}
