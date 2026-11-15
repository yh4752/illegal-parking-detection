package kr.ac.sejong.parking.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.integration.mqtt.support.MqttHeaders
import org.springframework.messaging.support.MessageBuilder

@ExtendWith(OutputCaptureExtension::class)
class MqttLearningMessageHandlerTest {
    private val handler = MqttLearningMessageHandler(ObjectMapper())

    @Test
    fun `JSON 항목을 읽어 구분해서 출력한다`(output: CapturedOutput) {
        send("""{"device_id":"edge-01","zone_id":"zone-01","device_seq":10}""")
        assertThat(output.all).contains("MQTT_LEARNING_PARSED")
            .contains("""device_id="edge-01" zone_id="zone-01" device_seq=10""")
    }

    @Test
    fun `문법 오류 뒤에도 다음 정상 메시지를 처리한다`(output: CapturedOutput) {
        send("""{"device_id":"edge-01"""")
        assertThat(output.all).contains("MQTT_LEARNING_INVALID_JSON")
            .doesNotContain("MQTT_LEARNING_PARSED")
        send("""{"device_id":"edge-02","zone_id":"zone-01","device_seq":11}""")
        assertThat(output.all).contains("MQTT_LEARNING_PARSED")
            .contains("""device_id="edge-02"""")
    }

    @Test
    fun `빈 입력과 뒤에 붙은 추가 JSON 값을 거절한다`(output: CapturedOutput) {
        send("   ")
        send("{} {}")
        assertThat(output.all).contains("reason=empty").contains("reason=syntax")
            .doesNotContain("MQTT_LEARNING_PARSED")
    }

    @Test
    fun `JSON 문법은 맞지만 객체가 아닌 입력을 구분한다`(output: CapturedOutput) {
        send("[]")
        assertThat(output.all).contains("MQTT_LEARNING_UNEXPECTED_SHAPE")
            .doesNotContain("MQTT_LEARNING_INVALID_JSON", "MQTT_LEARNING_PARSED")
    }

    @Test
    fun `512자 이후의 필드도 원문에서 파싱한다`(output: CapturedOutput) {
        send("""{"padding":"${"x".repeat(600)}","device_id":"edge-long","zone_id":"zone-01","device_seq":12}""")
        assertThat(output.all).contains("MQTT_LEARNING_PARSED")
            .contains("""device_id="edge-long" zone_id="zone-01" device_seq=12""")
    }

    @Test
    fun `파싱만으로 필수 필드 검증까지 통과한 것은 아니다`(output: CapturedOutput) {
        send("{}")
        assertThat(output.all).contains("MQTT_LEARNING_PARSED")
            .contains("device_id=null zone_id=null device_seq=null")
            .contains("MQTT_LEARNING_INVALID_DATA", "reason=device_id:required")
            .doesNotContain("MQTT_LEARNING_VALIDATED")
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "10", "1.0", "9007199254740991"])
    fun `정상 정수와 경계값을 허용한다`(sequence: String, output: CapturedOutput) {
        send("""{"device_id":"edge-01","zone_id":"zone-01","device_seq":$sequence}""")
        assertThat(output.all).contains("MQTT_LEARNING_PARSED", "MQTT_LEARNING_VALIDATED")
            .doesNotContain("MQTT_LEARNING_INVALID_DATA")
    }

    @ParameterizedTest
    @ValueSource(strings = ["-1", "\"10\"", "true", "null", "1.5", "9007199254740992",
        "9007199254740991.1", "999999999999999999999999999999999999"])
    fun `파싱 가능한 잘못된 순번을 거절한다`(sequence: String, output: CapturedOutput) {
        send("""{"device_id":"edge-01","zone_id":"zone-01","device_seq":$sequence}""")
        assertThat(output.all).contains("MQTT_LEARNING_PARSED", "MQTT_LEARNING_INVALID_DATA")
            .contains("reason=device_seq:")
            .doesNotContain("MQTT_LEARNING_VALIDATED", "MQTT_LEARNING_INVALID_JSON")
    }

    @ParameterizedTest
    @ValueSource(strings = ["device_id", "zone_id", "device_seq"])
    fun `필수 필드 누락을 구분한다`(field: String, output: CapturedOutput) {
        val root = ObjectMapper().createObjectNode()
            .put("device_id", "edge-01").put("zone_id", "zone-01").put("device_seq", 10)
        root.remove(field)
        send(root.toString())
        assertThat(output.all).contains("MQTT_LEARNING_INVALID_DATA", "reason=$field:required")
            .doesNotContain("MQTT_LEARNING_VALIDATED")
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"\"", "10", "null", "[]"])
    fun `잘못된 ID의 형식이나 빈 값을 거절한다`(value: String, output: CapturedOutput) {
        send("""{"device_id":$value,"zone_id":"zone-01","device_seq":10}""")
        send("""{"device_id":"edge-01","zone_id":$value,"device_seq":10}""")
        assertThat(output.all).contains("reason=device_id:", "reason=zone_id:")
            .doesNotContain("MQTT_LEARNING_VALIDATED")
    }

    @Test
    fun `ID 길이는 유니코드 문자 수로 경계를 검사한다`(output: CapturedOutput) {
        val id = "🚗".repeat(128)
        send("""{"device_id":"${id}x","zone_id":"zone-01","device_seq":10}""")
        send("""{"device_id":"edge-01","zone_id":"${id}x","device_seq":10}""")
        assertThat(output.all).contains("reason=device_id:length_1_to_128", "reason=zone_id:length_1_to_128")
            .doesNotContain("MQTT_LEARNING_VALIDATED")
        send("""{"device_id":"$id","zone_id":"$id","device_seq":10}""")
        assertThat(output.all).contains("MQTT_LEARNING_VALIDATED")
    }

    @Test
    fun `데이터 검증 실패 이후 정상 메시지를 계속 처리한다`(output: CapturedOutput) {
        send("""{"device_id":"edge-01","zone_id":"zone-01","device_seq":-1}""")
        assertThat(output.all).contains("MQTT_LEARNING_INVALID_DATA")
            .doesNotContain("MQTT_LEARNING_VALIDATED")
        send("""{"device_id":"edge-01","zone_id":"zone-01","device_seq":11}""")
        assertThat(output.all).contains("MQTT_LEARNING_VALIDATED")
    }

    private fun send(payload: String) {
        handler.handleMessage(MessageBuilder.withPayload(payload.toByteArray(Charsets.UTF_8))
            .setHeader(MqttHeaders.RECEIVED_TOPIC, "learning/hello").build())
    }
}
