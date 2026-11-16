package kr.ac.sejong.parkingtools

import org.flywaydb.core.Flyway
import org.springframework.boot.Banner
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/** Separate migrator process. Never starts web/MQTT, never disables a recovery hold. */
fun main(args: Array<String>) {
    require(args.contentEquals(arrayOf("--migrate"))) { "explicit_migrate_required" }
    val app = SpringApplication(DemoInitializationContext::class.java).apply {
        webApplicationType = WebApplicationType.NONE; setBannerMode(Banner.Mode.OFF)
    }
    app.run("--spring.profiles.active=database", "--spring.flyway.enabled=false").use { context ->
        val jdbc = context.getBean(JdbcTemplate::class.java)
        check(jdbc.queryForObject("SELECT current_user", String::class.java) == "parking_migrator") { "migration_account_required" }
        val flyway = Flyway.configure().dataSource(context.getBean(DataSource::class.java)).load()
        // migrate validates already-applied checksums while allowing new pending migrations.
        flyway.migrate(); flyway.validate()
        println("DATABASE_MIGRATED")
    }
}
