package kr.ac.sejong.parking.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ac.sejong.parking.device.adapter.jdbc.EventTransactionStore
import kr.ac.sejong.parking.device.adapter.jdbc.RevisionReservations
import kr.ac.sejong.parking.device.adapter.mqtt.EventIngestionMessageHandler
import kr.ac.sejong.parking.device.application.*
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.common.MqttSubscription
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.integration.channel.DirectChannel
import org.springframework.integration.dsl.IntegrationFlow
import org.springframework.integration.mqtt.event.MqttSubscribedEvent
import org.springframework.integration.mqtt.inbound.Mqttv5PahoMessageDrivenChannelAdapter
import org.springframework.integration.mqtt.outbound.Mqttv5PahoMessageHandler
import org.springframework.integration.mqtt.support.MqttHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.MessageBuilder
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration
@Profile("mqtt-ingest-local")
@EnableScheduling
class EventIngestionConfiguration(@Value("\${parking.mqtt.broker-url}") private val brokerUrl: String) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun eventTransactionStore(jdbc: JdbcTemplate, manager: PlatformTransactionManager, revisions: RevisionReservations, mapper: ObjectMapper) =
        EventTransactionStore(jdbc, TransactionTemplate(manager), revisions, mapper)

    @Bean
    fun eventIngestionService(mapper: ObjectMapper, store: EventTransactionStore) =
        EventIngestionService(EventMessageValidator(mapper) { _, _ -> true }, EventCanonicalizer(mapper), store)

    @Bean
    fun eventAckChannel(): MessageChannel = DirectChannel()

    @Bean
    fun eventAckOutbound(@Qualifier("eventAckChannel") channel: MessageChannel): IntegrationFlow {
        val handler = Mqttv5PahoMessageHandler(options(), "serverack0001").apply {
            setAsync(false)
            setDefaultQos(1)
            setDefaultRetained(false)
            setCompletionTimeout(5_000)
        }
        return IntegrationFlow.from(channel).handle(handler).get()
    }

    @Bean
    fun eventIngestInbound(service: EventIngestionService, mapper: ObjectMapper,
        @Qualifier("eventAckChannel") channel: MessageChannel): IntegrationFlow {
        val subscription = MqttSubscription("parking/v2/devices/+/events", 1).apply { setRetainAsPublished(true) }
        val adapter = Mqttv5PahoMessageDrivenChannelAdapter(options(), "serverin0001", subscription).apply {
            setPayloadType(ByteArray::class.java)
        }
        val handler = EventIngestionMessageHandler(service::receive, { topic, payload ->
            check(channel.send(MessageBuilder.withPayload(payload).setHeader(MqttHeaders.TOPIC, topic)
                .setHeader(MqttHeaders.QOS, 1).setHeader(MqttHeaders.RETAINED, false).build()))
        }, EventAckFactory(mapper))
        return IntegrationFlow.from(adapter).handle(handler).get()
    }

    @Bean
    fun eventRetentionJob(store: EventTransactionStore) = EventRetentionJob(store)

    @EventListener
    fun onSubscribed(event: MqttSubscribedEvent) {
        val adapter = event.source as? Mqttv5PahoMessageDrivenChannelAdapter ?: return
        if (adapter.componentName?.startsWith("eventIngestInbound.") != true) return
        log.info("EVENT_INGEST_READY topic=parking/v2/devices/+/events mode=local_persistence")
    }

    private fun options() = MqttConnectionOptions().apply {
        setServerURIs(arrayOf(brokerUrl))
        setCleanStart(true); setSessionExpiryInterval(0L)
        setKeepAliveInterval(15); setConnectionTimeout(10); setReceiveMaximum(8)
        setAutomaticReconnect(true); setAutomaticReconnectDelay(1, 30)
    }
}

class EventRetentionJob(private val store: EventTransactionStore) {
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    fun purge() { store.purgeExpired() }
}
