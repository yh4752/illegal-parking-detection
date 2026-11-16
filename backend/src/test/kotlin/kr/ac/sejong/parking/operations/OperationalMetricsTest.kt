package kr.ac.sejong.parking.operations

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kr.ac.sejong.parking.device.adapter.mqtt.EventIngestionMessageHandler
import kr.ac.sejong.parking.device.application.EventAckFactory
import kr.ac.sejong.parking.device.application.EventIngestResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.integration.mqtt.support.MqttHeaders
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.support.TransactionSynchronizationManager

class OperationalMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = OperationalMetrics(registry)
    private val committed = EventIngestResult.Committed("secret-device", "secret-message", "STORED", null)
    private fun send(receive: () -> EventIngestResult, publish: () -> Unit = {}) {
        EventIngestionMessageHandler({ _, _ -> receive() }, { _, _ -> publish() }, EventAckFactory(ObjectMapper()), metrics)
            .handleMessage(MessageBuilder.withPayload("secret-payload".toByteArray())
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "parking/v2/devices/secret-device/events")
                .setHeader(MqttHeaders.RECEIVED_QOS, 1).setHeader(MqttHeaders.RECEIVED_RETAINED, false).build())
    }
    private fun count(name: String, tag: String, value: String) = registry.find(name).tag(tag, value).counter()?.count()

    @Test fun `only completed commit is counted before ACK publication and labels exclude identities`() {
        send({ committed }) { assertThat(count("parking.ingest", "outcome", "stored")).isEqualTo(1.0) }
        assertThat(count("parking.ingest", "outcome", "stored")).isEqualTo(1.0)
        assertThat(count("parking.ack.publish", "result", "success")).isEqualTo(1.0)
        assertThat(registry.find("parking.ingest.commit").timer()?.count()).isEqualTo(1L)
        assertThat(registry.meters.flatMap { it.id.tags }.joinToString()).doesNotContain("secret-")
    }
    @Test fun `ACK failure leaves the committed result and records only publish failure`() {
        send({ committed }) { throw IllegalStateException("secret-error") }
        assertThat(count("parking.ingest", "outcome", "stored")).isEqualTo(1.0)
        assertThat(count("parking.ack.publish", "result", "failure")).isEqualTo(1.0)
        assertThat(registry.find("parking.commit.failures").counter()?.count()).isEqualTo(0.0)
    }
    @Test fun `database failure produces no committed timer or ACK`() {
        send({ throw IllegalStateException("wrapper", DataAccessResourceFailureException("secret-db")) })
        assertThat(count("parking.ingest", "outcome", "failed")).isEqualTo(1.0)
        assertThat(registry.find("parking.commit.failures").counter()?.count()).isEqualTo(1.0)
        assertThat(registry.find("parking.ingest.commit").timer()?.count()).isEqualTo(0L)
        assertThat(count("parking.ack.publish", "result", "success")).isEqualTo(0.0)
    }
    @Test fun `non database gate failure is not mislabeled as failed database commit`() {
        send({ throw IllegalStateException("revision_gate_closed") })
        assertThat(count("parking.ingest", "outcome", "failed")).isEqualTo(1.0)
        assertThat(registry.find("parking.commit.failures").counter()?.count()).isEqualTo(0.0)
    }
    @Test fun `unacknowledged reason is not a free form label`() {
        send({ EventIngestResult.NotAcknowledged("secret-reason") })
        assertThat(count("parking.ingest", "outcome", "not_acknowledged")).isEqualTo(1.0)
        assertThat(registry.meters.flatMap { it.id.tags }.joinToString()).doesNotContain("secret-")
    }
    @Test fun `caller owned transaction is never reported as a completed commit`() {
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try { send({ committed }) } finally { TransactionSynchronizationManager.clear() }
        assertThat(count("parking.ingest", "outcome", "stored")).isEqualTo(0.0)
        assertThat(count("parking.ingest", "outcome", "failed")).isEqualTo(1.0)
        assertThat(registry.find("parking.ingest.commit").timer()?.count()).isEqualTo(0L)
        assertThat(count("parking.ack.publish", "result", "success")).isEqualTo(0.0)
    }
}
