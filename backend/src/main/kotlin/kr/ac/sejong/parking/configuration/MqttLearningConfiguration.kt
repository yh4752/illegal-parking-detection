package kr.ac.sejong.parking.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.integration.dsl.IntegrationFlow
import org.springframework.integration.mqtt.event.MqttSubscribedEvent
import org.springframework.integration.mqtt.inbound.Mqttv5PahoMessageDrivenChannelAdapter

/** 학습용 수신·JSON 파싱만 수행한다. 제품 사건 처리·DB 저장·응용 ACK는 없다. */
@Configuration
@Profile("mqtt-learning")
class MqttLearningConfiguration(
    private val objectMapper: ObjectMapper,
    @Value("\${learning.mqtt.broker-url}") private val brokerUrl: String,
    @Value("\${learning.mqtt.topic}") private val topic: String,
    @Value("\${learning.mqtt.client-id}") private val clientId: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun mqttLearningInbound(): IntegrationFlow {
        // 브로커에 연결할 주소와 연결이 끊겼을 때의 재접속 동작을 설정한다.
        val options = MqttConnectionOptions().apply {
            setServerURIs(arrayOf(brokerUrl))
            setAutomaticReconnect(true)
            setConnectionTimeout(5)
            setCleanStart(true)
        }
        val inbound = Mqttv5PahoMessageDrivenChannelAdapter(options, clientId, topic)
        inbound.setQos(0) // 이번 실습은 송수신만 확인한다. 전달 보장 시험은 별도다.

        // 연결·구독은 여기서, 받은 내용의 파싱은 처리 클래스에서 맡는다.
        return IntegrationFlow.from(inbound)
            .handle(MqttLearningMessageHandler(objectMapper))
            .get()
    }

    // 서버 기동 로그와 MQTT 구독 성공을 구분한다. 이 로그 뒤에 메시지를 발행한다.
    @EventListener
    fun onSubscribed(event: MqttSubscribedEvent) {
        log.info("MQTT_LEARNING_READY topic={} broker={}", topic, brokerUrl)
    }
}
