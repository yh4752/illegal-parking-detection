package kr.ac.sejong.parking.operations

import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse
import org.eclipse.paho.mqttv5.common.MqttSubscription
import org.springframework.integration.mqtt.inbound.Mqttv5PahoMessageDrivenChannelAdapter
import org.springframework.integration.mqtt.core.Mqttv5ClientManager

/** Observe Paho callbacks; isRunning is lifecycle state, not a broker connection. */
class ObservedMqttInbound(options: MqttConnectionOptions, clientId: String,
    private val channel: MqttChannel, private val metrics: OperationalMetrics, vararg subscriptions: MqttSubscription
) : Mqttv5PahoMessageDrivenChannelAdapter(options, clientId, *subscriptions) {
    override fun connectComplete(reconnect: Boolean, serverURI: String) {
        super.connectComplete(reconnect, serverURI)
        metrics.mqttConnected(channel, true)
    }
    override fun disconnected(response: MqttDisconnectResponse) {
        metrics.mqttConnected(channel, false)
        super.disconnected(response)
    }
    override fun doStop() {
        metrics.mqttConnected(channel, false)
        super.doStop()
    }
}

class ObservedAckClient(options: MqttConnectionOptions, clientId: String, private val metrics: OperationalMetrics
) : Mqttv5ClientManager(options, clientId) {
    override fun connectComplete(reconnect: Boolean, serverURI: String) {
        super.connectComplete(reconnect, serverURI)
        metrics.mqttConnected(MqttChannel.ACKS, true)
    }
    override fun disconnected(response: MqttDisconnectResponse) {
        metrics.mqttConnected(MqttChannel.ACKS, false)
        super.disconnected(response)
    }
    override fun stop() {
        metrics.mqttConnected(MqttChannel.ACKS, false)
        super.stop()
    }
}
