package kr.ac.sejong.parking.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ac.sejong.parking.auth.*
import kr.ac.sejong.parking.web.*
import kr.ac.sejong.parking.zone.adapter.jdbc.JdbcReadStore
import kr.ac.sejong.parking.zone.application.*
import kr.ac.sejong.parking.device.adapter.jdbc.RevisionReservations
import org.springframework.context.annotation.*
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.http.HttpMethod
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.util.Base64

fun webPasswordEncoder(): PasswordEncoder = object : PasswordEncoder {
    private val delegate = BCryptPasswordEncoder(12)
    override fun encode(rawPassword: CharSequence): String = delegate.encode(rawPassword)
    override fun matches(rawPassword: CharSequence, encodedPassword: String): Boolean =
        try { delegate.matches(rawPassword, encodedPassword) } catch (_: IllegalArgumentException) { false }
}

@Configuration
@Profile("web-api")
class WebApiConfiguration {
    @Bean fun webClock(): Clock = Clock.systemUTC()
    @Bean fun restErrors(mapper: ObjectMapper) = RestErrors(mapper)
    @Bean fun strictJson(mapper: ObjectMapper) = StrictJson(mapper)
    @Bean fun webUsers(jdbc: JdbcTemplate) = JdbcWebUsers(jdbc)
    @Bean fun passwordEncoder() = webPasswordEncoder()
    @Bean fun sessionLifetime(webClock: Clock) = SessionLifetime(webClock)
    @Bean fun loginThrottle(webClock: Clock) = LoginThrottle(webClock)
    @Bean fun eventCursor(mapper: ObjectMapper, json: StrictJson, environment: Environment): EventCursor {
        val key = try { Base64.getDecoder().decode(environment.getRequiredProperty("PARKING_CURSOR_HMAC_KEY")) }
            catch (_: IllegalArgumentException) { error("PARKING_CURSOR_HMAC_KEY must be base64 of at least 32 random bytes") }
        return EventCursor(mapper, json, key)
    }
    @Bean fun readStore(jdbc: JdbcTemplate, manager: PlatformTransactionManager, mapper: ObjectMapper, cursor: EventCursor) =
        JdbcReadStore(jdbc, manager, mapper, cursor)
    @Bean fun readQueries(store: JdbcReadStore, revisions: RevisionReservations, webClock: Clock) = ReadQueries(store, revisions, webClock)

    @Bean fun webSecurity(http: HttpSecurity, mapper: ObjectMapper, users: JdbcWebUsers, encoder: PasswordEncoder,
        json: StrictJson, errors: RestErrors, throttle: LoginThrottle, lifetime: SessionLifetime,
        streams: kr.ac.sejong.parking.zone.adapter.sse.ZoneStreams, environment: Environment): SecurityFilterChain {
        val contexts = HttpSessionSecurityContextRepository().apply { setDisableUrlRewriting(true) }
        val csrf = HttpSessionCsrfTokenRepository()
        val provider = DaoAuthenticationProvider(users).apply { setPasswordEncoder(encoder) }
        val login = JsonLoginFilter(ProviderManager(provider), json, throttle, errors).apply {
            setSecurityContextRepository(contexts)
            setSessionAuthenticationStrategy(CompositeSessionAuthenticationStrategy(listOf(
                ChangeSessionIdAuthenticationStrategy(), CsrfAuthenticationStrategy(csrf))))
            setAuthenticationSuccessHandler { request, response, authentication ->
                lifetime.start(request.getSession(true))
                response.contentType = "application/json"; response.characterEncoding = "UTF-8"
                response.setHeader("Cache-Control", "no-store")
                mapper.writeValue(response.outputStream, (authentication.principal as WebIdentity).response())
            }
            setAuthenticationFailureHandler { request, response, _ ->
                contexts.saveContext(SecurityContextHolder.createEmptyContext(), request, response)
                errors.write(request, response, ApiProblem(401, "UNAUTHENTICATED", "아이디 또는 비밀번호를 확인해 주세요."))
            }
        }
        http.securityContext { it.securityContextRepository(contexts).requireExplicitSave(true) }
        http.csrf { it.csrfTokenRepository(csrf) }
        http.requestCache { it.disable() }; http.formLogin { it.disable() }; http.httpBasic { it.disable() }
        http.exceptionHandling { it.authenticationEntryPoint { req, res, _ ->
            errors.write(req, res, ApiProblem(401, "UNAUTHENTICATED", "로그인이 필요합니다."))
        }.accessDeniedHandler { req, res, _ -> errors.write(req, res, ApiProblem(403, "FORBIDDEN", "접근 권한이 없습니다.")) } }
        http.authorizeHttpRequests { it
            .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
            .requestMatchers(org.springframework.http.HttpMethod.GET, "/actuator/health", "/api/csrf").permitAll()
            .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/login").permitAll()
            .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/auth/me", "/api/zones/*/status", "/api/events", "/api/events/*", "/api/stream").hasAnyRole("VIEWER", "OPERATOR")
            .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/logout").authenticated()
            .anyRequest().denyAll()
        }
        val logoutPath = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/logout")
        http.logout { it.logoutRequestMatcher { req -> logoutPath.matches(req) && SecurityContextHolder.getContext().authentication?.principal is WebIdentity }
            .addLogoutHandler { req, _, _ -> req.getSession(false)?.let { session -> streams.closeSession(session.id) } }
            .deleteCookies(environment.getProperty("server.servlet.session.cookie.name", "JSESSIONID")).logoutSuccessHandler { _, res, _ -> res.status = 204 } }
        http.addFilterBefore(SessionGuardFilter(users, lifetime, contexts, errors), CsrfFilter::class.java)
        http.addFilterAt(login, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}
