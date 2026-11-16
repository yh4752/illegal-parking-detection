package kr.ac.sejong.parking.device.adapter.mqtt

import kr.ac.sejong.parking.device.application.EventAckFactory
import kr.ac.sejong.parking.device.application.EventIngestResult
import kr.ac.sejong.parking.operations.OperationalMetrics
import org.slf4j.LoggerFactory
import org.springframework.integration.mqtt.support.MqttHeaders
import org.springframework.messaging.Message
import org.springframework.messaging.MessageHandler
import org.springframework.transaction.support.TransactionSynchronizationManager

class EventIngestionMessageHandler(
    private val receive: (String, ByteArray) -> EventIngestResult,
    private val publishAck: (String, ByteArray) -> Unit,
    private val ackFactory: EventAckFactory,
    private val metrics: OperationalMetrics? = null,
) : MessageHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handleMessage(message: Message<*>) {
        val topic = message.headers[MqttHeaders.RECEIVED_TOPIC] as? String
        val bytes = message.payload as? ByteArray
        if (topic == null || bytes == null || message.headers[MqttHeaders.RECEIVED_QOS] != 1 ||
            message.headers[MqttHeaders.RECEIVED_RETAINED] != false
        ) {
            metrics?.notAcknowledged()
            log.warn("EVENT_NOT_ACKNOWLEDGED reason=transport ack_sent=false")
            return
        }
        val started = System.nanoTime()
        val result = try { receive(topic, bytes) } catch (error: Exception) {
            metrics?.failed(error)
            if (error is InterruptedException) Thread.currentThread().interrupt()
            log.error("EVENT_INGEST_UNAVAILABLE cause={} ack_sent=false", error.javaClass.simpleName)
            return
        }
        when (result) {
            is EventIngestResult.NotAcknowledged -> {
                metrics?.notAcknowledged()
                log.warn("EVENT_NOT_ACKNOWLEDGED ack_sent=false")
            }
            is EventIngestResult.Committed -> {
                // This adapter must not acknowledge a result inside a caller-owned transaction.
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                    metrics?.failed()
                    log.error("EVENT_NOT_ACKNOWLEDGED reason=transaction_still_active ack_sent=false")
                    return
                }
                metrics?.committed(result.status, System.nanoTime() - started)
                log.info("EVENT_COMMITTED status={} error_code={}", result.status, result.errorCode)
                try {
                    publishAck("parking/v2/devices/${result.deviceId}/acks", ackFactory.create(result))
                    metrics?.ackPublished(true)
                    log.info("EVENT_ACK_PUBLISHED status={} ack_sent=true", result.status)
                } catch (_: Exception) {
                    // The DB commit remains final. The same message will replay its receipt.
                    metrics?.ackPublished(false)
                    log.warn("EVENT_ACK_UNAVAILABLE status={} ack_sent=false", result.status)
                }
            }
        }
    }
}
