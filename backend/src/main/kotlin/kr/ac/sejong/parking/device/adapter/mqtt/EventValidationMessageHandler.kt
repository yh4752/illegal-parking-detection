package kr.ac.sejong.parking.device.adapter.mqtt

import kr.ac.sejong.parking.device.application.EventMessageValidator
import kr.ac.sejong.parking.device.application.EventValidationResult
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.integration.mqtt.support.MqttHeaders
import org.springframework.messaging.Message
import org.springframework.messaging.MessageHandler

/** Local validation entry point. Never publishes STORED, DUPLICATE or REJECTED. */
class EventValidationMessageHandler(private val validator: EventMessageValidator) : MessageHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handleMessage(message: Message<*>) {
        val topic = message.headers[MqttHeaders.RECEIVED_TOPIC] as? String
        val payload = message.payload as? ByteArray
        if (topic == null || payload == null) {
            log.warn("EVENT_INVALID reason=transport_shape ack_sent=false")
            return
        }
        if (message.headers[MqttHeaders.RECEIVED_QOS] != 1 ||
            message.headers[MqttHeaders.RECEIVED_RETAINED] != false
        ) {
            log.warn("EVENT_INVALID reason=delivery_options ack_sent=false")
            return
        }
        val result = try {
            validator.validate(topic, payload)
        } catch (_: DataAccessException) {
            log.error("EVENT_VALIDATION_UNAVAILABLE reason=database ack_sent=false")
            return
        }
        when (result) {
            is EventValidationResult.Invalid -> log.warn(
                "EVENT_INVALID reason={} rule_id={} ack_sent=false", result.reason, result.ruleId ?: "none",
            )
            is EventValidationResult.Validated -> log.info(
                "EVENT_CONTRACT_VALIDATED device_id={} message_id={} device_seq={} stored=false ack_sent=false",
                // JsonNode string output escapes control characters; do not log the full payload.
                result.message["device_id"], result.message["message_id"], result.message["device_seq"],
            )
        }
    }
}
