package kr.ac.sejong.parking.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ac.sejong.parking.device.adapter.jdbc.RevisionReservations
import org.springframework.context.annotation.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path

@Configuration
@Profile("mqtt-ingest-local", "web-api")
class RevisionConfiguration {
    @Bean(destroyMethod = "close")
    @DependsOn("flywayInitializer")
    fun revisionReservations(
        jdbc: JdbcTemplate, manager: PlatformTransactionManager, mapper: ObjectMapper, environment: Environment,
        @Value("\${parking.revision.primary}") primary: String,
        @Value("\${parking.revision.backup}") backup: String,
    ): RevisionReservations {
        check(environment.activeProfiles.none { it in setOf("mqtt-validation-local", "mqtt-learning") }) {
            "Run product ingestion/web without validation or learning profiles"
        }
        return RevisionReservations(jdbc, TransactionTemplate(manager), mapper, Path.of(primary).toAbsolutePath().normalize(),
            Path.of(backup).toAbsolutePath().normalize()).apply { initialize() }
    }

}
