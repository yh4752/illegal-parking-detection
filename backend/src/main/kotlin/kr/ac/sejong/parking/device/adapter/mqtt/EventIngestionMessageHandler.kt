package kr.ac.sejong.parking.device.adapter.mqtt

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ac.sejong.parking.device.application.EventAckFactory
import kr.ac.sejong.parking.device.application.EventIngestResult
import org.slf4j.LoggerFactory
import org.springframework.integration.mqtt.support.MqttHeaders
import org.springframework.messaging.Message
import org.springframework.messaging.MessageHandler
import org.springframework.transaction.support.TransactionSynchronizationManager

class EventIngestionMessageHandler(
    private val receive: (String, ByteArray) -> EventIngestResult,
    private val publishAck: (String, ByteArray) -> Unit,
    private val ackFactory: EventAckFactory,
) : MessageHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()

    override fun handleMessage(message: Message<*>) {
        val topic = message.headers[MqttHeaders.RECEIVED_TOPIC] as? String
        val bytes = message.payload as? ByteArray
        if (topic == null || bytes == null || message.headers[MqttHeaders.RECEIVED_QOS] != 1 ||
            message.headers[MqttHeaders.RECEIVED_RETAINED] != false
        ) {
            log.warn("EVENT_NOT_ACKNOWLEDGED reason=transport ack_sent=false")
            return
        }
        val result = try { receive(topic, bytes) } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            log.error("EVENT_INGEST_UNAVAILABLE cause={} ack_sent=false", error.javaClass.simpleName)
            return
        }
        when (result) {
            is EventIngestResult.NotAcknowledged -> log.warn("EVENT_NOT_ACKNOWLEDGED reason={} ack_sent=false", result.reason)
            is EventIngestResult.Committed -> {
                // This adapter must not acknowledge a result inside a caller-owned transaction.
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                    log.error("EVENT_NOT_ACKNOWLEDGED reason=transaction_still_active ack_sent=false")
                    return
                }
                val id = mapper.writeValueAsString(result.messageId)
                log.info("EVENT_COMMITTED message_id={} status={} error_code={}", id, result.status, result.errorCode)
                try {
                    publishAck("parking/v2/devices/${result.deviceId}/acks", ackFactory.create(result))
                    log.info("EVENT_ACK_PUBLISHED message_id={} status={} ack_sent=true", id, result.status)
                } catch (_: Exception) {
                    // The DB commit remains final. The same message will replay its receipt.
                    log.warn("EVENT_ACK_UNAVAILABLE message_id={} status={} ack_sent=false", id, result.status)
                }
            }
        }
    }
}
