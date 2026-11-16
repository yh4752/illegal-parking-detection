package kr.ac.sejong.parking.configuration

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.env.MockEnvironment
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import java.nio.file.Path

class ProductionConfigurationTest {
    @TempDir lateinit var dir: Path
    private fun production() = MockEnvironment().apply {
        setActiveProfiles("app-prod", "web-api", "database", "mqtt-ingest")
        withProperty("spring.flyway.enabled", "false")
        withProperty("spring.datasource.username", "parking_runtime")
        withProperty("server.servlet.session.cookie.secure", "true")
        withProperty("parking.mqtt.broker-url", "ssl://broker:8883")
        for (key in listOf("spring.datasource.password", "parking.mqtt.password", "PARKING_CURSOR_HMAC_KEY")) withProperty(key,"test-only")
        withProperty("parking.mqtt.username", "parking-server")
        withProperty("parking.mqtt.ca-file", "/test/ca.crt")
        withProperty("PARKING_DB_URL", "jdbc:postgresql://postgres/parking")
        withProperty("PARKING_REVISION_PRIMARY", "/state/primary.json")
        withProperty("PARKING_REVISION_BACKUP", "/other/copy.json")
        withProperty("PARKING_SECRETS_DIR", "$dir/")
        for (name in listOf("POSTGRES_PASSWORD", "parking.mqtt.password", "PARKING_CURSOR_HMAC_KEY")) {
            dir.resolve(name).toFile().writeText("test-only")
        }
    }
    @Test fun `운영은 로컬 프로필을 순서와 관계없이 거부한다`() {
        for (local in listOf("app-local", "mqtt-ingest-local", "mqtt-validation-local", "mqtt-learning")) {
            val env=production(); env.addActiveProfile(local)
            assertThatThrownBy { ProductionEnvironment.validate(env) }.hasMessageContaining("production_local_profile_forbidden")
        }
    }
    @Test fun `운영은 자동 마이그레이션과 평문 MQTT와 비보안 쿠키를 거부한다`() {
        for ((key,value) in listOf("spring.flyway.enabled" to "true", "spring.datasource.username" to "parking_migrator", "parking.mqtt.broker-url" to "tcp://broker:1883", "server.servlet.session.cookie.secure" to "false", "parking.mqtt.username" to "edge-01")) {
            val env=production().withProperty(key,value)
            assertThatThrownBy { ProductionEnvironment.validate(env) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }
    @Test fun `운영은 필수 설정 누락을 비밀값 없이 보고한다`() {
        val env=production().withProperty("parking.mqtt.password", "")
        assertThatThrownBy { ProductionEnvironment.validate(env) }.hasMessage("production_setting_required:parking.mqtt.password")
        ProductionEnvironment.validate(production())
    }
    @Test fun `로컬 MQTT 옵션과 재접속 경계는 유지한다`() {
        val env=MockEnvironment().withProperty("parking.mqtt.broker-url", "tcp://127.0.0.1:11883")
        env.setActiveProfiles("mqtt-ingest-local")
        ProductionEnvironment.validate(env)
        val options=MqttTransportConfiguration(env).options()
        assertThat(options.serverURIs).containsExactly("tcp://127.0.0.1:11883")
        assertThat(options.userName).isNull()
        assertThat(options.isHttpsHostnameVerificationEnabled).isTrue()
        assertThat(options.maxReconnectDelay).isEqualTo(8000)
    }
    @Test fun `잘못된 CA나 URI와 불완전 계정으로 수신 클라이언트를 만들지 않는다`() {
        assertThatThrownBy { MqttTransportConfiguration(production()) }.isInstanceOf(java.nio.file.NoSuchFileException::class.java)
        for (url in listOf("http://broker:8883", "ssl://name:password@broker:8883", "tcp://broker:1883/path")) {
            assertThatThrownBy { MqttTransportConfiguration(MockEnvironment().withProperty("parking.mqtt.broker-url",url)) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { MqttTransportConfiguration(MockEnvironment().withProperty("parking.mqtt.broker-url","tcp://localhost:1883").withProperty("parking.mqtt.username","incomplete")) }.hasMessage("mqtt_credentials_pair_required")
    }
    @Test fun `실제 운영 프로필은 configtree 비밀값과 프로필 그룹을 읽고 Flyway를 끈다`() {
        for ((name,value) in mapOf("POSTGRES_PASSWORD" to "test-db", "parking.mqtt.password" to "test-mqtt", "PARKING_CURSOR_HMAC_KEY" to "test-key")) dir.resolve(name).toFile().writeText(value)
        ApplicationContextRunner().withInitializer(ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=app-prod", "PARKING_SECRETS_DIR=$dir/", "PARKING_DB_URL=jdbc:postgresql://postgres/parking", "PARKING_MQTT_URL=ssl://broker:8883", "PARKING_MQTT_CA_FILE=/test/ca.crt", "PARKING_REVISION_PRIMARY=/a/file", "PARKING_REVISION_BACKUP=/b/file")
            .run { context ->
                assertThat(context).hasNotFailed()
                ProductionEnvironment.validate(context.environment)
                assertThat(context.environment.getProperty("spring.datasource.password")).isEqualTo("test-db")
                assertThat(context.environment.getProperty("parking.mqtt.password")).isEqualTo("test-mqtt")
            }
    }

    @Test fun `운영은 비밀값 디렉터리가 없으면 시작하지 않는다`() {
        ApplicationContextRunner().withInitializer(ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=app-prod", "PARKING_SECRETS_DIR=$dir/missing/")
            .run { context -> assertThat(context).hasFailed() }
    }

    @Test fun `환경변수에 비밀값이 있어도 필수 파일 누락은 거부한다`() {
        for (name in listOf("POSTGRES_PASSWORD", "parking.mqtt.password", "PARKING_CURSOR_HMAC_KEY")) {
            val env = production()
            dir.resolve(name).toFile().delete()
            assertThatThrownBy { ProductionEnvironment.validate(env) }
                .hasMessage("production_secret_file_required:$name")
        }
    }

    @Test fun `환경변수나 명령줄로 파일의 비밀값을 덮어쓰면 거부한다`() {
        assertThatThrownBy { ProductionEnvironment.validate(production().withProperty("parking.mqtt.password", "override")) }
            .hasMessage("production_secret_source_mismatch:parking.mqtt.password")
    }

    @Test fun `운영은 필수 프로필과 각각의 필수 설정을 검사한다`() {
        val env = production()
        env.setActiveProfiles("app-prod", "database", "web-api")
        assertThatThrownBy { ProductionEnvironment.validate(env) }.hasMessage("production_profiles_required")
        for (key in listOf("spring.datasource.password", "parking.mqtt.password", "parking.mqtt.ca-file",
            "PARKING_CURSOR_HMAC_KEY", "PARKING_DB_URL", "PARKING_REVISION_PRIMARY", "PARKING_REVISION_BACKUP")) {
            assertThatThrownBy { ProductionEnvironment.validate(production().withProperty(key, "")) }
                .hasMessage("production_setting_required:$key")
        }
    }
}
