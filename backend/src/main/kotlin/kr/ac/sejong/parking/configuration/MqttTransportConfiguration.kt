package kr.ac.sejong.parking.configuration

import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

@Configuration
@Profile("mqtt-ingest-local", "mqtt-ingest")
class MqttTransportConfiguration(private val env: Environment) {
    // Validate/build once. All three Paho clients use the same trust and identity policy.
    private val url = env.getRequiredProperty("parking.mqtt.broker-url")
    private val production = "app-prod" in env.activeProfiles
    private val uri = URI(url).also {
        require(it.scheme in setOf("tcp", "ssl") && it.host != null && it.userInfo == null && it.query == null && it.fragment == null && it.path.isNullOrEmpty()) { "mqtt_broker_uri_invalid" }
        require(!production || it.scheme == "ssl") { "production_mqtt_tls_required" }
    }
    private val mqttUsername = env.getProperty("parking.mqtt.username")
    private val mqttPassword = env.getProperty("parking.mqtt.password")
    private val tlsSocketFactory = if (uri.scheme == "ssl") {
        val ca = Path.of(env.getRequiredProperty("parking.mqtt.ca-file"))
        val certificates = Files.newInputStream(ca).use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
        require(certificates.isNotEmpty()) { "mqtt_ca_required" }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        certificates.forEachIndexed { i, cert -> store.setCertificateEntry("ca-$i", cert) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
    } else null

    init {
        require((mqttUsername == null) == (mqttPassword == null)) { "mqtt_credentials_pair_required" }
        if (production) require(!mqttUsername.isNullOrBlank() && !mqttPassword.isNullOrBlank()) { "mqtt_credentials_required" }
    }

    fun options() = MqttConnectionOptions().apply {
        setServerURIs(arrayOf(url))
        setCleanStart(true); setSessionExpiryInterval(0L)
        setKeepAliveInterval(15); setConnectionTimeout(10); setReceiveMaximum(8)
        setAutomaticReconnect(true); setMaxReconnectDelay(8_000)
        setHttpsHostnameVerificationEnabled(true)
        if (tlsSocketFactory != null) setSocketFactory(tlsSocketFactory)
        if (mqttUsername != null) { setUserName(mqttUsername); setPassword(mqttPassword!!.toByteArray(Charsets.UTF_8)) }
    }
}
