package kr.ac.sejong.parking.configuration

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Environment
import java.nio.file.Files
import java.nio.file.Path

/** Runs after config data (including mandatory configtree), before any connection or migration. */
class ProductionEnvironment : EnvironmentPostProcessor, Ordered {
    override fun getOrder() = Ordered.LOWEST_PRECEDENCE
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) = validate(environment)

    companion object {
        fun validate(env: Environment) {
            val profiles = env.activeProfiles.toSet()
            if ("app-prod" !in profiles) return
            require(profiles.none { it.endsWith("-local") || it == "mqtt-learning" }) { "production_local_profile_forbidden" }
            require(profiles.containsAll(setOf("database", "web-api", "mqtt-ingest"))) { "production_profiles_required" }
            require(env.getProperty("spring.flyway.enabled") == "false") { "production_runtime_migration_forbidden" }
            require(env.getProperty("spring.datasource.username") == "parking_runtime") { "production_runtime_account_required" }
            require(env.getProperty("server.servlet.session.cookie.secure") == "true") { "production_secure_cookie_required" }
            require(env.getProperty("parking.mqtt.broker-url")?.startsWith("ssl://") == true) { "production_mqtt_tls_required" }
            for (key in listOf("spring.datasource.password", "parking.mqtt.username", "parking.mqtt.password", "parking.mqtt.ca-file", "PARKING_CURSOR_HMAC_KEY", "PARKING_DB_URL", "PARKING_REVISION_PRIMARY", "PARKING_REVISION_BACKUP")) {
                require(!env.getProperty(key).isNullOrBlank()) { "production_setting_required:$key" }
            }
            require(env.getProperty("parking.mqtt.username") == "parking-server") { "production_mqtt_server_account_required" }
            val secrets = Path.of(env.getProperty("PARKING_SECRETS_DIR", "/run/secrets/"))
            for ((name, property) in mapOf("POSTGRES_PASSWORD" to "spring.datasource.password",
                "parking.mqtt.password" to "parking.mqtt.password", "PARKING_CURSOR_HMAC_KEY" to "PARKING_CURSOR_HMAC_KEY")) {
                val file = secrets.resolve(name)
                require(Files.isRegularFile(file) && Files.isReadable(file) && Files.size(file) in 1..4096) {
                    "production_secret_file_required:$name"
                }
                val value = Files.readString(file).trimEnd('\r', '\n')
                require(value.isNotBlank() && env.getProperty(property) == value) { "production_secret_source_mismatch:$name" }
            }
        }
    }
}
