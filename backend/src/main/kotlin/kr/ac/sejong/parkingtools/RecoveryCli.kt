package kr.ac.sejong.parkingtools

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kr.ac.sejong.parking.device.adapter.jdbc.RevisionLedger
import kr.ac.sejong.parking.device.application.EventCanonicalizer
import kr.ac.sejong.parking.operations.RevisionRecovery
import org.springframework.boot.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    require(args.isNotEmpty() && args[0] in setOf("prepare", "fence", "adopt", "reconcile", "accept"))
    val options = args.drop(1).associate { arg ->
        require(arg.startsWith("--") && '=' in arg)
        val (key, value) = arg.substring(2).split('=', limit = 2)
        require(key in setOf("reason", "report-sha256", "acknowledge-loss")); key to value
    }
    val application = SpringApplication(DemoInitializationContext::class.java).apply {
        webApplicationType = WebApplicationType.NONE; setBannerMode(Banner.Mode.OFF)
    }
    application.run("--spring.main.web-application-type=none", "--spring.profiles.active=database", "--spring.flyway.enabled=false").use { context ->
        val jdbc = context.getBean(JdbcTemplate::class.java)
        check(jdbc.queryForObject("SELECT current_user", String::class.java) == "parking_migrator") { "offline_owner_required" }
        val flyway = org.flywaydb.core.Flyway.configure().dataSource(jdbc.dataSource!!).load()
        flyway.validate(); check(flyway.info().pending().isEmpty()) { "database_migrations_required" }
        val mapper = context.getBean(ObjectMapper::class.java)
        val env = context.environment
        val recovery = RevisionRecovery(jdbc, TransactionTemplate(context.getBean(PlatformTransactionManager::class.java)), mapper,
            Path.of(env.getRequiredProperty("PARKING_REVISION_PRIMARY")), Path.of(env.getRequiredProperty("PARKING_REVISION_BACKUP")),
            checkNotNull(RevisionLedger.configured(env, mapper)) { "independent_ledger_required" })
        val reportPath = Path.of("/recovery/report.json")
        val result = when (args[0]) {
            "reconcile" -> recovery.reconcile(Files.readAllBytes(Path.of("/recovery/device-report.json")))
            "accept" -> {
                check(options["acknowledge-loss"] == "true") { "explicit_backup_data_loss_acknowledgement_required" }
                val bytes = Files.readAllBytes(reportPath)
                check(EventCanonicalizer.sha256(bytes) == options["report-sha256"]) { "explicit_report_hash_required" }
                recovery.accept(mapper.readTree(bytes) as ObjectNode, options.getValue("reason"))
            }
            else -> recovery.reserve(args[0], options.getValue("reason"))
        }
        Files.write(reportPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result))
        println(mapper.writeValueAsString(mapper.createObjectNode().put("result", "RECOVERY_" + result["stage"].asText())
            .put("report_sha256", EventCanonicalizer.sha256(Files.readAllBytes(reportPath))).put("http_started", false).put("mqtt_started", false)))
    }
}
