package kr.ac.sejong.parking.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ac.sejong.parking.device.adapter.jdbc.EventTransactionStore
import kr.ac.sejong.parking.device.application.*
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.common.MqttSubscription
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.*
import org.springframework.integration.dsl.IntegrationFlow
import org.springframework.integration.mqtt.inbound.Mqttv5PahoMessageDrivenChannelAdapter
import org.springframework.integration.mqtt.support.MqttHeaders
import org.springframework.messaging.MessageHandler
import org.springframework.scheduling.annotation.Scheduled

@Configuration
@Profile("mqtt-ingest-local")
class TelemetryConfiguration(@Value("\${parking.mqtt.broker-url}") private val broker: String) {
    @Bean fun heartbeatReceiver(mapper: ObjectMapper, store: EventTransactionStore) = HeartbeatReceiver(mapper, store)
    @Bean fun telemetryInbound(receiver: HeartbeatReceiver): IntegrationFlow {
        val options = MqttConnectionOptions().apply {
            setServerURIs(arrayOf(broker)); setCleanStart(true); setSessionExpiryInterval(0L)
            setKeepAliveInterval(15); setConnectionTimeout(10); setAutomaticReconnect(true); setAutomaticReconnectDelay(1,30)
        }
        val adapter = Mqttv5PahoMessageDrivenChannelAdapter(options,"servertelemetry0001",
            MqttSubscription("parking/v2/devices/+/heartbeat",0).apply { setRetainAsPublished(true) },
            MqttSubscription("parking/v2/devices/+/availability",1).apply { setRetainAsPublished(true) }).apply { setPayloadType(ByteArray::class.java) }
        return IntegrationFlow.from(adapter).handle(MessageHandler { message ->
            val topic = message.headers[MqttHeaders.RECEIVED_TOPIC] as? String ?: return@MessageHandler
            val bytes = message.payload as? ByteArray ?: return@MessageHandler
            receiver.receive(topic, bytes, message.headers[MqttHeaders.RECEIVED_QOS] as? Int,
                message.headers[MqttHeaders.RECEIVED_RETAINED] as? Boolean)
        }).get()
    }
}

class HeartbeatReceiver(mapper: ObjectMapper, private val store: EventTransactionStore) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val heartbeat = EventMessageValidator(mapper, { _,_->true }, "Heartbeat")
    private val availability = EventMessageValidator(mapper, { _,_->true }, "Availability")
    private val canonical = EventCanonicalizer(mapper)
    fun receive(topic: String, bytes: ByteArray, qos: Int?, retained: Boolean?) {
        try {
            val diagnostic = topic.endsWith("/availability")
            if (!diagnostic && (qos != 0 || retained != false)) { log.warn("HEARTBEAT_IGNORED reason=transport"); return }
            val validator = if(diagnostic) availability else heartbeat
            val parsed = validator.parse(topic,bytes)
            if(parsed !is EventParseResult.Parsed) { log.warn("TELEMETRY_IGNORED reason=parse"); return }
            val invalid = validator.validateContent(parsed.message)
            if(invalid != null || parsed.topicDevice != parsed.message["device_id"].asText()) {
                log.warn("TELEMETRY_IGNORED reason={}", invalid?.reason ?: "topic_device_mismatch"); return
            }
            val m = parsed.message
            if(diagnostic) {
                // Includes retained online/will messages. No state or freshness authority.
                log.debug("AVAILABILITY_DIAGNOSTIC status={} retained={}",m["status"].asText(),retained); return
            }
            val result = store.heartbeat(CorrelatedDeviceMessage(m["device_id"].asText(),m["zone_id"].asText(),m["message_id"].asText(),topic,bytes,m,canonical.hash(m)))
            if(result is EventIngestResult.NotAcknowledged) log.debug("HEARTBEAT_IGNORED reason={}",result.reason)
        } catch(error: Exception) { log.error("TELEMETRY_UNAVAILABLE cause={}",error.javaClass.simpleName) }
    }
    @Scheduled(fixedDelay=1000,initialDelay=1000)
    fun expire(){try{store.expireConnectivity()}catch(error:Exception){log.error("CONNECTIVITY_UNAVAILABLE cause={}",error.javaClass.simpleName)}}
}
