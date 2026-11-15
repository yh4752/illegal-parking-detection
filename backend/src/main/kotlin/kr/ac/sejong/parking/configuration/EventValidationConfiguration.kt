package kr.ac.sejong.parking.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ac.sejong.parking.device.adapter.jdbc.JdbcDeviceRegistrationLookup
import kr.ac.sejong.parking.device.adapter.mqtt.EventValidationMessageHandler
import kr.ac.sejong.parking.device.application.EventMessageValidator
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.common.MqttSubscription
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.integration.dsl.IntegrationFlow
import org.springframework.integration.mqtt.event.MqttSubscribedEvent
import org.springframework.integration.mqtt.inbound.Mqttv5PahoMessageDrivenChannelAdapter
import org.springframework.jdbc.core.JdbcTemplate

@Configuration
@Profile("mqtt-validation-local")
class EventValidationConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun eventMessageValidator(mapper: ObjectMapper, jdbc: JdbcTemplate) =
        EventMessageValidator(mapper, JdbcDeviceRegistrationLookup(jdbc))

    @Bean
    fun eventValidationInbound(validator: EventMessageValidator): IntegrationFlow {
        val options = MqttConnectionOptions().apply {
            setServerURIs(arrayOf("tcp://127.0.0.1:11883")) // Explicit local-only transport.
            setCleanStart(true)
            setSessionExpiryInterval(0L)
            setKeepAliveInterval(15)
            setConnectionTimeout(10)
            setReceiveMaximum(8)
            setAutomaticReconnect(true)
            setMaxReconnectDelay(8_000) // Paho 1.2.5 reconnect loop uses this millisecond property.
        }
        val subscription = MqttSubscription("parking/v2/devices/+/events", 1).apply {
            // Preserve the publisher's retain flag, including live delivery, for validation.
            setRetainAsPublished(true)
        }
        val inbound = Mqttv5PahoMessageDrivenChannelAdapter(options, "serverin0001", subscription)
        inbound.setPayloadType(ByteArray::class.java) // Preserve bytes for strict UTF-8 validation.
        return IntegrationFlow.from(inbound).handle(EventValidationMessageHandler(validator)).get()
    }

    @EventListener
    fun onSubscribed(event: MqttSubscribedEvent) {
        log.info("EVENT_VALIDATION_READY topic=parking/v2/devices/+/events mode=local_validation_only")
    }
}
