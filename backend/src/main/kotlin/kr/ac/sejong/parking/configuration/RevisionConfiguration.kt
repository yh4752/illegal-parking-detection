package kr.ac.sejong.parking.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ac.sejong.parking.device.adapter.jdbc.RevisionReservations
import kr.ac.sejong.parking.device.adapter.jdbc.RevisionLedger
import org.springframework.context.annotation.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path

@Configuration
@Profile("mqtt-ingest-local", "mqtt-ingest", "web-api")
class RevisionConfiguration {
    @Bean(destroyMethod = "close")
    fun revisionReservations(
        flyway: org.springframework.beans.factory.ObjectProvider<org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer>,
        jdbc: JdbcTemplate, manager: PlatformTransactionManager, mapper: ObjectMapper, environment: Environment,
        @Value("\${parking.revision.primary}") primary: String,
        @Value("\${parking.revision.backup}") backup: String,
    ): RevisionReservations {
        flyway.ifAvailable // Finish local migration when enabled; production migrates separately.
        if ("app-prod" in environment.activeProfiles) {
            val role = jdbc.queryForMap("SELECT session_user AS login, current_user AS name, rolsuper, rolcreatedb, rolcreaterole, rolbypassrls FROM pg_roles WHERE rolname=current_user")
            check(role["login"] == "parking_runtime" && role["name"] == "parking_runtime" &&
                listOf("rolsuper", "rolcreatedb", "rolcreaterole", "rolbypassrls").all { role[it] == false }) { "production_database_role_invalid" }
            check(jdbc.queryForObject("SELECT NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=current_user))", Boolean::class.java) == true) { "production_runtime_role_membership_forbidden" }
            check(jdbc.queryForObject("SELECT NOT EXISTS(SELECT 1 FROM pg_class WHERE relowner=(SELECT oid FROM pg_roles WHERE rolname=current_user) AND relnamespace=current_schema()::regnamespace)", Boolean::class.java) == true) { "production_runtime_object_ownership_forbidden" }
            check(jdbc.queryForObject("SELECT has_schema_privilege(current_user,current_schema(),'CREATE')", Boolean::class.java) == false) { "production_schema_create_forbidden" }
            val migration = org.flywaydb.core.Flyway.configure().dataSource(jdbc.dataSource!!).load()
            migration.validate()
            check(migration.info().pending().isEmpty()) { "production_migrations_required" }
        }
        check(environment.activeProfiles.none { it in setOf("mqtt-validation-local", "mqtt-learning") }) {
            "Run product ingestion/web without validation or learning profiles"
        }
        return RevisionReservations(jdbc, TransactionTemplate(manager), mapper, Path.of(primary).toAbsolutePath().normalize(),
            Path.of(backup).toAbsolutePath().normalize(), RevisionLedger.configured(environment, mapper)).apply { initialize() }
    }

}
