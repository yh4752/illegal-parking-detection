package kr.ac.sejong.parking.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ac.sejong.parking.device.adapter.jdbc.EventTransactionStore
import kr.ac.sejong.parking.device.adapter.jdbc.RevisionReservations
import kr.ac.sejong.parking.device.adapter.mqtt.EventIngestionMessageHandler
import kr.ac.sejong.parking.device.adapter.mqtt.ConfirmedAckPublisher
import kr.ac.sejong.parking.device.application.*
import kr.ac.sejong.parking.operations.*
import org.eclipse.paho.mqttv5.common.MqttSubscription
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.integration.channel.DirectChannel
import org.springframework.integration.dsl.IntegrationFlow
import org.springframework.integration.mqtt.event.MqttSubscribedEvent
import org.springframework.integration.mqtt.inbound.Mqttv5PahoMessageDrivenChannelAdapter
import org.springframework.integration.mqtt.support.MqttHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessageHandler
import org.springframework.messaging.support.MessageBuilder
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration
@Profile("mqtt-ingest-local", "mqtt-ingest")
@EnableScheduling
class EventIngestionConfiguration(private val transport: MqttTransportConfiguration) {
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
    fun ackMqttClient(metrics: OperationalMetrics) =
        ObservedAckClient(transport.options(), "serverack0001", metrics).apply { setCompletionTimeout(5_000) }

    @Bean
    fun eventAckOutbound(@Qualifier("eventAckChannel") channel: MessageChannel, client: ObservedAckClient): IntegrationFlow {
        val publisher=ConfirmedAckPublisher { client.client }
        return IntegrationFlow.from(channel).handle(MessageHandler { message ->
            publisher.publish(message.headers[MqttHeaders.TOPIC] as String, message.payload as ByteArray)
        }).get()
    }

    @Bean
    fun eventIngestInbound(service: EventIngestionService, mapper: ObjectMapper, metrics: OperationalMetrics,
        @Qualifier("eventAckChannel") channel: MessageChannel): IntegrationFlow {
        val subscription = MqttSubscription("parking/v2/devices/+/events", 1).apply { setRetainAsPublished(true) }
        val adapter = ObservedMqttInbound(transport.options(), "serverin0001", MqttChannel.EVENTS, metrics, subscription).apply {
            setPayloadType(ByteArray::class.java)
        }
        val handler = EventIngestionMessageHandler(service::receive, { topic, payload ->
            check(channel.send(MessageBuilder.withPayload(payload).setHeader(MqttHeaders.TOPIC, topic)
                .setHeader(MqttHeaders.QOS, 1).setHeader(MqttHeaders.RETAINED, false).build()))
        }, EventAckFactory(mapper), metrics)
        return IntegrationFlow.from(adapter).handle(handler).get()
    }

    @Bean
    fun eventRetentionJob(store: EventTransactionStore) = EventRetentionJob(store)

    @EventListener
    fun onSubscribed(event: MqttSubscribedEvent) {
        val adapter = event.source as? Mqttv5PahoMessageDrivenChannelAdapter ?: return
        if (adapter.componentName?.startsWith("eventIngestInbound.") != true) return
        log.info("EVENT_INGEST_READY topic=parking/v2/devices/+/events mode=persistence")
    }


}

class EventRetentionJob(private val store: EventTransactionStore) {
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    fun purge() { store.purgeExpired() }
}
