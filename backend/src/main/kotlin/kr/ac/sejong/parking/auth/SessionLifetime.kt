package kr.ac.sejong.parking.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import kr.ac.sejong.parking.web.ApiProblem
import kr.ac.sejong.parking.web.RestErrors
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.time.Duration

class SessionLifetime(private val clock: Clock) {
    companion object { const val STARTED = "parking.login_at"; const val ACTIVE = "parking.http_activity_at" }
    fun start(session: HttpSession) {
        session.maxInactiveInterval = 1800
        session.setAttribute(STARTED, clock.millis()); session.setAttribute(ACTIVE, clock.millis())
    }
    fun valid(session: HttpSession): Boolean = try {
        val start = session.getAttribute(STARTED) as? Long
        val active = session.getAttribute(ACTIVE) as? Long
        val now = clock.millis()
        start != null && active != null && now >= start && now >= active &&
            now - start < Duration.ofHours(8).toMillis() && now - active < Duration.ofMinutes(30).toMillis()
    } catch (_: IllegalStateException) { false }
    fun touch(session: HttpSession) { session.setAttribute(ACTIVE, clock.millis()) }
}
class SessionGuardFilter(
    private val users: JdbcWebUsers, private val lifetime: SessionLifetime,
    private val repository: SecurityContextRepository, private val errors: RestErrors,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest) = !request.requestURI.removePrefix(request.contextPath).startsWith("/api/")
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val old = SecurityContextHolder.getContext().authentication?.principal as? WebIdentity
        if (old != null) {
            try {
                val session = request.getSession(false)
                val fresh = if (session != null && lifetime.valid(session)) users.activeIdentity(old.userId) else null
                if (fresh == null) {
                    SecurityContextHolder.clearContext()
                    try { session?.invalidate() } catch (_: IllegalStateException) { }
                    if (request.requestURI.removePrefix(request.contextPath) != "/api/csrf" && request.requestURI.removePrefix(request.contextPath) != "/api/auth/login") {
                        errors.write(request, response, ApiProblem(401, "UNAUTHENTICATED", "다시 로그인해 주세요.")); return
                    }
                } else {
                    if (fresh != old) {
                        val context = SecurityContextHolder.createEmptyContext()
                        context.authentication = UsernamePasswordAuthenticationToken.authenticated(fresh, null, fresh.authorities())
                        SecurityContextHolder.setContext(context); repository.saveContext(context, request, response)
                    }
                    if (request.requestURI.removePrefix(request.contextPath) != "/api/stream") lifetime.touch(session!!)
                }
            } catch (error: Exception) { errors.write(request, response, errors.infrastructure(error, request)); return }
        }
        chain.doFilter(request, response)
    }
}
