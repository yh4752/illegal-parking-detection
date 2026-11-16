package kr.ac.sejong.parking.device.adapter.mqtt

import org.eclipse.paho.mqttv5.client.IMqttAsyncClient

/** MQTT5 completion also happens for negative PUBACKs; inspect the broker reason code. */
class ConfirmedAckPublisher(private val client: () -> IMqttAsyncClient) {
    fun publish(topic: String, payload: ByteArray) {
        val token=client().publish(topic, payload, 1, false)
        token.waitForCompletion(5_000)
        // Success(0) and No matching subscribers(16) mean accepted by the broker,
        // not that a device received or processed the application ACK.
        check(token.reasonCodes?.singleOrNull() in setOf(0,16)) { "mqtt_puback_rejected" }
    }
}
