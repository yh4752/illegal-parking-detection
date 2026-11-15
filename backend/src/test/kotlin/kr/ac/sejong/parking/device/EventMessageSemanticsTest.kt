package kr.ac.sejong.parking.device

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kr.ac.sejong.parking.device.application.EventMessageValidator
import kr.ac.sejong.parking.device.application.EventValidationResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.util.stream.Stream

class EventMessageSemanticsTest {
    private val validator = EventMessageValidator(mapper) { _, _ -> true }
    private fun result(message: JsonNode) = validator.validate(topic, mapper.writeValueAsBytes(message))
    private fun valid(message: JsonNode) {
        assertThat(result(message)).isEqualTo(EventValidationResult.Validated(message))
    }
    private fun rule(message: JsonNode, ruleId: String) {
        val actual = result(message)
        assertThat(actual).isInstanceOf(EventValidationResult.Invalid::class.java)
        assertThat((actual as EventValidationResult.Invalid).ruleId).isEqualTo(ruleId)
    }
    private fun fixture(id: String): ObjectNode = applyCase(cases.single { it["id"].asText() == id })

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("designCases")
    fun `확정한 설계의 정상과 모순 예제를 같은 수신 검증기로 검사한다`(
        id: String, description: String, case: JsonNode,
    ) {
        assertThat(case["expected_schema_valid"].booleanValue()).isTrue()
        val message = applyCase(case)
        if (case["expected_semantic_v1_valid"].booleanValue()) valid(message)
        else rule(message, case["primary_rule"].asText())
    }

    @ParameterizedTest
    @CsvSource(
        "E01,incident_state,\"HELD\"",
        "E01,event_policy_version,4",
        "E01,guidance_count,2",
        "E01,continuous_stop_ms,0",
        "E01,body_type,\"BUS\"",
        "E01,use_type,\"TAXI\"",
        "E23,overdue,false",
    )
    fun `같은 사건은 일곱 공통 요약 값의 모순을 거부한다`(id: String, field: String, value: String) {
        val message = fixture(id)
        (message["snapshot"] as ObjectNode).set<JsonNode>(field, mapper.readTree(value))
        rule(message, "V11")
    }

    @Test
    fun `정수와 소수 표기가 다른 동일 수치는 모순이 아니다`() {
        val message = fixture("E01")
        (message["snapshot"] as ObjectNode).apply {
            put("event_policy_version", BigDecimal("3.000"))
            put("received_policy_version", BigDecimal("3.0"))
            put("guidance_count", BigDecimal("1.0"))
            put("continuous_stop_ms", BigDecimal("304000.000"))
        }
        valid(message)
    }

    @Test
    fun `활성 중지는 재개 확인 대기보다 우선하고 해제되면 대기 모드로 남는다`() {
        val message = fixture("E03")
        val active = message["snapshot"]["pauses"][0].deepCopy<ObjectNode>()
        val acknowledgement = examples["manual_close_event"]["snapshot"]["pauses"][0]
        val snapshot = message["snapshot"] as ObjectNode
        snapshot.putArray("pauses").add(active).add(acknowledgement)
        valid(message)
        snapshot.put("mode", "AWAITING_RESUME")
        rule(message, "V05")
        snapshot.putArray("pauses").add(acknowledgement)
        valid(message)
        snapshot.putArray("pauses")
        rule(message, "V05")
        snapshot.put("mode", "AUTO")
        valid(message)
    }

    @Test
    fun `현재 정책과 사건이 없어도 과거 사건 보고는 허용한다`() {
        val message = fixture("E01")
        val snapshot = message["snapshot"] as ObjectNode
        for (field in listOf("current_event_id", "incident_state", "event_policy_version", "body_type", "use_type")) {
            snapshot.putNull(field)
        }
        snapshot.put("guidance_count", 0).put("continuous_stop_ms", 0).put("overdue", false)
            .putNull("active_policy_version").putNull("received_policy_version")
            .put("camera_health", "RECONFIGURE")
        valid(message)
        snapshot.put("camera_health", "OK")
        rule(message, "V06")
    }

    @Test
    fun `적용한 정책이 있으면 받은 정책이 필요하되 사건 버전은 다를 수 있다`() {
        val message = fixture("E01")
        val snapshot = message["snapshot"] as ObjectNode
        snapshot.putNull("received_policy_version")
        rule(message, "V06")
        snapshot.put("received_policy_version", 5).put("active_policy_version", 4)
        valid(message) // The incident still uses version 3.
    }

    @ParameterizedTest
    @ValueSource(strings = ["OBSERVING", "STOP_CONFIRMED", "GUIDING", "GUIDED", "LONG_UNRESOLVED"])
    fun `서로 다른 사건이어도 각 요약의 상태와 횟수 및 미해소 조합을 검사한다`(state: String) {
        for (target in listOf("event", "snapshot")) {
            val message = fixture("E04") // historical event and current snapshot differ.
            val summary = message[target] as ObjectNode
            summary.put(if (target == "event") "state" else "incident_state", state)
            if (state in setOf("GUIDING", "GUIDED")) summary.put("guidance_count", 0)
            rule(message, "V08")
        }
    }

    @Test
    fun `과거 요약의 미해소 상태 모순도 현재 snapshot과 별도로 거부한다`() {
        val message = fixture("E04")
        (message["event"] as ObjectNode).put("overdue", true)
        rule(message, "V09")
    }

    @ParameterizedTest
    @ValueSource(strings = ["EVENT_CLOSED_EXIT", "EVENT_CLOSED_MANUAL"])
    fun `종료 사건은 종료 요약을 포함해야 한다`(eventType: String) {
        val message = fixture("E01").put("event_type", eventType).putNull("event")
        rule(message, "V10")
        message.set<JsonNode>("event", examples["manual_close_event"]["event"].deepCopy<JsonNode>())
        (message["snapshot"] as ObjectNode).put("current_event_id", "next-event")
        if (eventType == "EVENT_CLOSED_EXIT") {
            (message["event"] as ObjectNode).put("state", "CLOSED_EXIT").put("close_reason", "OBSERVED_EXIT")
        }
        valid(message)
    }

    @ParameterizedTest
    @CsvSource("camera_health,SUSPECT", "camera_health,BLOCKED", "camera_health,NO_FRAME", "camera_health,RECONFIGURE", "output_status,FAILED", "output_status,UNKNOWN")
    fun `현재 관측 및 출력 장애는 보류 상태가 필요하다`(field: String, value: String) {
        val message = fixture("E01")
        (message["snapshot"] as ObjectNode).put(field, value)
        rule(message, "V12")
        (message["event"] as ObjectNode).put("state", "HELD")
        (message["snapshot"] as ObjectNode).put("incident_state", "HELD")
        valid(message)
    }

    @ParameterizedTest
    @ValueSource(strings = ["E09", "E10"])
    fun `시각 불신과 신뢰 복구 대기는 모두 보류 상태가 필요하다`(id: String) {
        val message = fixture(id)
        (message["event"] as ObjectNode).put("state", "GUIDED")
        (message["snapshot"] as ObjectNode).put("incident_state", "GUIDED")
        rule(message, "V12")
    }

    @Test
    fun `운영 중지와 현재 사건 표시의 모순을 거부하되 중단 중 출력은 허용한다`() {
        val message = fixture("E01")
        val snapshot = message["snapshot"] as ObjectNode
        snapshot.putArray("pauses").add(examples["automatic_photo_available"]["snapshot"]["pauses"][0])
        snapshot.put("mode", "PAUSED")
        rule(message, "V12")
        (message["event"] as ObjectNode).put("state", "HELD")
        snapshot.put("incident_state", "HELD").put("output_status", "STOPPING")
        valid(message)
    }

    @Test
    fun `순번 점프와 부팅 세대 변경은 메시지 자체의 모순이 아니다`() {
        val message = fixture("E01").put("device_seq", 9_007_199_254_740_991L).put("boot_id", "restarted")
        (message["snapshot"] as ObjectNode).put("device_seq", 9_007_199_254_740_991L).put("boot_id", "restarted")
        valid(message)
    }

    @Test
    fun `의미 모순은 등록 DB 조회 전에 거부하며 뒤의 정상 입력은 그대로 검사한다`() {
        var calls = 0
        val checker = EventMessageValidator(mapper) { _, _ -> calls++; true }
        val invalid = checker.validate(topic, mapper.writeValueAsBytes(fixture("E11")))
        assertThat((invalid as EventValidationResult.Invalid).ruleId).isEqualTo("V04")
        assertThat(calls).isZero()
        assertThat(checker.validate(topic, mapper.writeValueAsBytes(fixture("E01"))))
            .isInstanceOf(EventValidationResult.Validated::class.java)
        assertThat(calls).isEqualTo(1)
    }

    companion object {
        private val mapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        private val examples = EventMessageSemanticsTest::class.java.getResourceAsStream("/contracts/examples.json")!!
            .use { mapper.readTree(it) }
        private val cases = EventMessageSemanticsTest::class.java.getResourceAsStream("/design/event-validation-cases.json")!!
            .use { mapper.readTree(it)["cases"].toList() }
        private const val topic = "parking/v2/devices/edge-01/events"

        @JvmStatic
        fun designCases(): Stream<Arguments> = cases.map {
            Arguments.of(it["id"].asText(), it["description"].asText(), it)
        }.stream()

        private fun applyCase(case: JsonNode): ObjectNode {
            val message = requireNotNull(examples[case["base_example"].asText()]).deepCopy<ObjectNode>()
            for ((pointer, value) in case["changes"].properties()) {
                val slash = pointer.lastIndexOf('/')
                val parent = message.at(pointer.substring(0, slash)) as ObjectNode
                val field = pointer.substring(slash + 1).replace("~1", "/").replace("~0", "~")
                require(parent.has(field)) { "Fixture ${case["id"]}: unknown path $pointer" }
                parent.set<JsonNode>(field, value.deepCopy<JsonNode>())
            }
            return message
        }
    }
}
