package kr.ac.sejong.parking.device

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ac.sejong.parking.device.adapter.mqtt.EventValidationMessageHandler
import kr.ac.sejong.parking.device.application.EventMessageValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.integration.mqtt.support.MqttHeaders
import org.springframework.messaging.support.MessageBuilder

@ExtendWith(OutputCaptureExtension::class)
class EventValidationMessageHandlerTest {
    private val mapper = ObjectMapper()
    private val payload = javaClass.getResourceAsStream("/contracts/examples.json")!!.use {
        mapper.writeValueAsBytes(mapper.readTree(it)["guidance_complete"])
    }
    private fun message(bytes: ByteArray = payload, qos: Int = 1, retained: Boolean = false) =
        MessageBuilder.withPayload(bytes)
            .setHeader(MqttHeaders.RECEIVED_TOPIC, "parking/v2/devices/edge-01/events")
            .setHeader(MqttHeaders.RECEIVED_QOS, qos)
            .setHeader(MqttHeaders.RECEIVED_RETAINED, retained).build()

    @Test
    fun `오류 뒤 정상 메시지를 계속 검사하고 저장했다고 로그하지 않는다`(output: CapturedOutput) {
        val handler = EventValidationMessageHandler(EventMessageValidator(mapper) { _, _ -> true })
        handler.handleMessage(message("{".toByteArray()))
        handler.handleMessage(message())
        assertThat(output.all).contains("EVENT_INVALID reason=json", "EVENT_CONTRACT_VALIDATED", "stored=false ack_sent=false")
        assertThat(output.all).doesNotContain("stored=true", "ack_sent=true", "continuous_stop_ms")
    }

    @Test
    fun `의미 모순의 규칙 ID를 기록하고 다음 정상 메시지 수신을 계속한다`(output: CapturedOutput) {
        val handler = EventValidationMessageHandler(EventMessageValidator(mapper) { _, _ -> true })
        val invalid = mapper.readTree(payload)
        (invalid["snapshot"] as com.fasterxml.jackson.databind.node.ObjectNode).put("guidance_count", 2)
        handler.handleMessage(message(mapper.writeValueAsBytes(invalid)))
        handler.handleMessage(message())
        assertThat(output.all).contains(
            "EVENT_INVALID reason=same_event_guidance_count rule_id=V11 ack_sent=false",
            "EVENT_CONTRACT_VALIDATED", "stored=false ack_sent=false",
        )
        assertThat(output.all).doesNotContain("stored=true", "ack_sent=true", "continuous_stop_ms")
    }

    @Test
    fun `QoS0와 retained 사건은 검증 통과로 처리하지 않는다`(output: CapturedOutput) {
        val handler = EventValidationMessageHandler(EventMessageValidator(mapper) { _, _ -> true })
        handler.handleMessage(message(qos = 0))
        handler.handleMessage(message(retained = true))
        assertThat(output.all).contains("EVENT_INVALID reason=delivery_options")
        assertThat(output.all).doesNotContain("EVENT_CONTRACT_VALIDATED")
    }

    @Test
    fun `DB 장애는 입력 오류와 구별하고 회복 뒤 다음 메시지를 검사한다`(output: CapturedOutput) {
        var unavailable = true
        val handler = EventValidationMessageHandler(EventMessageValidator(mapper) { _, _ ->
            if (unavailable) throw DataAccessResourceFailureException("unavailable")
            true
        })
        handler.handleMessage(message())
        unavailable = false
        handler.handleMessage(message())
        assertThat(output.all).contains("EVENT_VALIDATION_UNAVAILABLE reason=database", "EVENT_CONTRACT_VALIDATED")
        assertThat(output.all).doesNotContain("EVENT_INVALID", "ack_sent=true")
    }
}
