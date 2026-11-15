package kr.ac.sejong.parking.configuration

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import org.slf4j.LoggerFactory
import org.springframework.integration.mqtt.support.MqttHeaders
import org.springframework.messaging.Message
import org.springframework.messaging.MessageHandler

/** 학습용 세 필드만 검증한다. 정식 사건 전체·장치 등록·DB 저장을 검증하지 않는다. */
internal class MqttLearningMessageHandler(objectMapper: ObjectMapper) : MessageHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    // JSON 뒤에 다른 값이나 쓰레기 문자가 붙어 있어도 오류로 구분한다.
    private val reader = objectMapper.reader()
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    override fun handleMessage(message: Message<*>) {
        val topic = message.headers[MqttHeaders.RECEIVED_TOPIC]
        val payload = (message.payload as ByteArray).toString(Charsets.UTF_8)
        log.info("MQTT_LEARNING_RECEIVED topic={} payload={}", topic, preview(payload))

        val root = try {
            reader.readTree(payload) // 로그용으로 자르거나 변형하기 전의 원문을 읽는다.
        } catch (exception: JsonProcessingException) {
            log.warn("MQTT_LEARNING_INVALID_JSON topic={} reason=syntax", topic)
            return // 이번 메시지만 처리를 끝낸다. 다음 메시지는 계속 받는다.
        }

        if (root == null || root.isMissingNode) {
            log.warn("MQTT_LEARNING_INVALID_JSON topic={} reason=empty", topic)
            return
        }
        if (!root.isObject) {
            log.warn("MQTT_LEARNING_UNEXPECTED_SHAPE topic={} expected=object", topic)
            return
        }

        // 필드가 없어도 여기서는 null로 표시한다. PARSED는 제품 데이터 검증 성공이 아니다.
        log.info("MQTT_LEARNING_PARSED topic={} device_id={} zone_id={} device_seq={}",
            topic, preview(root.get("device_id")?.toString() ?: "null"),
            preview(root.get("zone_id")?.toString() ?: "null"), preview(root.get("device_seq")?.toString() ?: "null"))

        val error = validateLearningFields(root)
        if (error != null) {
            log.warn("MQTT_LEARNING_INVALID_DATA topic={} reason={}", topic, error)
            return
        }
        log.info("MQTT_LEARNING_VALIDATED topic={} scope=three_fields", topic)
    }

    // v1.43 Schema의 세 필드 규칙을 학습용 입력에 적용한다. 다른 필드는 여기서 검사하지 않는다.
    private fun validateLearningFields(root: JsonNode): String? {
        for (name in listOf("device_id", "zone_id")) {
            val value = root.get(name)
            if (value == null || value.isNull) return "$name:required"
            if (!value.isTextual) return "$name:string_required"
            val text = value.textValue()
            val length = text.codePointCount(0, text.length)
            if (length !in 1..128) return "$name:length_1_to_128"
        }

        val sequence = root.get("device_seq")
        if (sequence == null || sequence.isNull) return "device_seq:required"
        if (!sequence.isNumber) return "device_seq:integer_required"
        // JSON Schema의 integer는 숫자의 값이 정수인지 검사한다. 문자열 "10"은 변환하지 않는다.
        val number = sequence.decimalValue()
        if (number.stripTrailingZeros().scale() > 0) return "device_seq:integer_required"
        if (number < BigDecimal.ZERO || number > BigDecimal("9007199254740991")) {
            return "device_seq:out_of_range"
        }
        return null
    }

    private fun preview(value: String): String =
        value.replace("\r", "\\r").replace("\n", "\\n").take(512)
}
