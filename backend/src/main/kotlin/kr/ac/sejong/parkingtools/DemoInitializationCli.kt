package kr.ac.sejong.parkingtools

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ac.sejong.parking.device.adapter.jdbc.RevisionReservations
import kr.ac.sejong.parking.operations.DemoInitializationRequest
import kr.ac.sejong.parking.operations.FreshDemoInitializer
import org.springframework.boot.Banner
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.flywaydb.core.Flyway
import javax.sql.DataSource
import java.nio.file.Path

// Deliberately outside kr.ac.sejong.parking's component scan. No web or MQTT configurations imported.
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class])
class DemoInitializationContext

data class InitializationArguments(val request: DemoInitializationRequest, val apply: Boolean) {
    companion object {
        fun parse(args: Array<String>): InitializationArguments {
            val values = mutableMapOf<String, String>()
            val allowed = setOf("zone-id", "device-id", "policy-version", "reason")
            var apply = false
            for (arg in args) {
                if (arg == "--apply") {
                    require(!apply) { "duplicate_apply_flag" }; apply = true; continue
                }
                require(arg.startsWith("--") && '=' in arg) { "use_named_arguments_with_equals" }
                val (key, value) = arg.substring(2).split('=', limit = 2)
                require(key in allowed && key !in values) { "unknown_or_duplicate_argument" }
                values[key] = value
            }
            require(values.keys == allowed) { "required: --zone-id --device-id --policy-version --reason; optional: --apply" }
            val version = values.getValue("policy-version").toLongOrNull() ?: error("invalid_policy_version")
            return InitializationArguments(DemoInitializationRequest(values.getValue("zone-id"), values.getValue("device-id"),
                version, values.getValue("reason")), apply)
        }
    }
}

fun main(args: Array<String>) {
    val options = InitializationArguments.parse(args)
    val application = SpringApplication(DemoInitializationContext::class.java).apply {
        webApplicationType = WebApplicationType.NONE
        setBannerMode(Banner.Mode.OFF)
        setAdditionalProfiles("database")
    }
    // CLI preflight must not migrate the DB or start a web server through environment defaults.
    application.run("--spring.main.web-application-type=none", "--spring.profiles.active=database",
        "--spring.flyway.enabled=false").use { context ->
        val jdbc = context.getBean(JdbcTemplate::class.java)
        val schema = jdbc.queryForObject("SELECT current_schema()", String::class.java)
        val flyway = Flyway.configure().dataSource(context.getBean(DataSource::class.java))
            .defaultSchema(schema).locations("classpath:db/migration").load()
        flyway.validate()
        check(flyway.info().pending().isEmpty()) { "database_migrations_required" }
        val mapper = context.getBean(ObjectMapper::class.java)
        val transaction = TransactionTemplate(context.getBean(PlatformTransactionManager::class.java))
        val initializer = FreshDemoInitializer(jdbc, transaction, mapper)
        initializer.inspect(options.request)
        if (options.apply) {
            val primary = Path.of(context.environment.getRequiredProperty("PARKING_REVISION_PRIMARY")).toAbsolutePath().normalize()
            val backup = Path.of(context.environment.getRequiredProperty("PARKING_REVISION_BACKUP")).toAbsolutePath().normalize()
            RevisionReservations(jdbc, transaction, mapper, primary, backup).use { revisions ->
                revisions.initialize()
                initializer.apply(options.request, revisions)
            }
        }
        println(mapper.writeValueAsString(mapper.createObjectNode().apply {
            put("result", if (options.apply) "INITIALIZED" else "DB_PREFLIGHT_PASSED")
            put("zone_id", options.request.zoneId); put("device_id", options.request.deviceId)
            put("manifest_checked", options.apply); put("recovery_hold", !options.apply)
            put("http_started", false); put("mqtt_started", false)
        }))
    }
}
