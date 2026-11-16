package kr.ac.sejong.parking.configuration

import io.micrometer.core.instrument.MeterRegistry
import kr.ac.sejong.parking.device.adapter.jdbc.RevisionReservations
import kr.ac.sejong.parking.operations.ReadinessProbe
import kr.ac.sejong.parking.operations.BackupStatusMetrics
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.User
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import java.nio.file.Files
import java.nio.file.Path

internal fun monitoringPassword(secret: Path, configured: String): String {
    require(Files.isRegularFile(secret) && Files.size(secret) in 32..4096) { "monitoring_secret_file_required" }
    val password=Files.readString(secret).trimEnd('\r','\n')
    require(password.length >= 32 && password.isNotBlank() && '\r' !in password && '\n' !in password) { "monitoring_secret_value_invalid" }
    require(password == configured) { "monitoring_secret_source_mismatch" }
    return password
}

@Configuration(proxyBeanMethods = false)
@Profile("monitoring")
class MonitoringConfiguration {
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty("PARKING_BACKUP_STATUS_FILE")
    fun backupStatusMetrics(env: Environment, mapper: com.fasterxml.jackson.databind.ObjectMapper, registry: MeterRegistry) =
        BackupStatusMetrics(Path.of(env.getRequiredProperty("PARKING_BACKUP_STATUS_FILE")), mapper, registry)

    @Bean(initMethod="start", destroyMethod="close")
    fun parkingReadiness(jdbc: JdbcTemplate, revisions: RevisionReservations, registry: MeterRegistry) =
        ReadinessProbe(jdbc, revisions, registry)

    @Bean @Order(0)
    fun monitoringSecurity(http: HttpSecurity, env: Environment): SecurityFilterChain {
        val port=env.getRequiredProperty("management.server.port", Int::class.java)
        require(port > 0 && port != env.getProperty("server.port", Int::class.java, 8080)) { "separate_management_port_required" }
        val secret=Path.of(env.getProperty("PARKING_SECRETS_DIR", "/run/secrets/")).resolve("parking.monitoring.password")
        val password=monitoringPassword(secret, env.getRequiredProperty("parking.monitoring.password"))
        val encoder=BCryptPasswordEncoder()
        val users=InMemoryUserDetailsManager(User.withUsername("prometheus").password(encoder.encode(password)).roles("METRICS").build())
        val provider=DaoAuthenticationProvider(users).apply { setPasswordEncoder(encoder) }
        http.securityMatcher { request -> request.localPort == port }
        http.authenticationManager(ProviderManager(provider))
        http.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        http.requestCache { it.disable() }; http.csrf { it.disable() }; http.formLogin { it.disable() }; http.logout { it.disable() }
        http.httpBasic { it.realmName("parking-metrics") }
        http.authorizeHttpRequests { it
            .requestMatchers(HttpMethod.GET, "/actuator/prometheus", "/actuator/health/liveness", "/actuator/health/readiness").hasRole("METRICS")
            .anyRequest().denyAll()
        }
        return http.build()
    }
}
