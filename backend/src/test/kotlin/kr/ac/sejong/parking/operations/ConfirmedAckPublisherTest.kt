package kr.ac.sejong.parking.operations

import kr.ac.sejong.parking.device.adapter.mqtt.ConfirmedAckPublisher
import org.assertj.core.api.Assertions.*
import org.eclipse.paho.mqttv5.client.IMqttAsyncClient
import org.eclipse.paho.mqttv5.client.IMqttToken
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class ConfirmedAckPublisherTest {
    private val client=mock(IMqttAsyncClient::class.java)
    private val token=mock(IMqttToken::class.java)
    private val payload=byteArrayOf(1)
    private val publisher=ConfirmedAckPublisher { client }
    init { `when`(client.publish("acks",payload,1,false)).thenReturn(token) }
    @Test fun `completed negative PUBACK is failure even when waitForCompletion returns normally`() {
        `when`(token.reasonCodes).thenReturn(intArrayOf(135))
        assertThatThrownBy { publisher.publish("acks",payload) }.hasMessage("mqtt_puback_rejected")
    }
    @Test fun `success and no matching subscribers are broker accepted publications`() {
        for(code in listOf(0,16)) {
            `when`(token.reasonCodes).thenReturn(intArrayOf(code))
            assertThatCode { publisher.publish("acks",payload) }.doesNotThrowAnyException()
        }
        verify(token,times(2)).waitForCompletion(5_000)
    }
    @Test fun `missing reason is not proof of broker acceptance`() {
        `when`(token.reasonCodes).thenReturn(null)
        assertThatThrownBy { publisher.publish("acks",payload) }.hasMessage("mqtt_puback_rejected")
    }
}
