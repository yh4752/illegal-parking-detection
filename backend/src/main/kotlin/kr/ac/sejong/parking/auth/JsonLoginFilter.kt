package kr.ac.sejong.parking.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kr.ac.sejong.parking.web.*
import org.springframework.http.MediaType
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.http.HttpMethod

class JsonLoginFilter(manager: AuthenticationManager, private val json: StrictJson,
    private val throttle: LoginThrottle, private val errors: RestErrors) :
    AbstractAuthenticationProcessingFilter(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/login"), manager) {
    override fun attemptAuthentication(request: HttpServletRequest, response: HttpServletResponse): Authentication? {
        try {
            val type = try { MediaType.parseMediaType(request.contentType ?: "") } catch (_: IllegalArgumentException) { invalidRequest() }
            if (!MediaType.APPLICATION_JSON.isCompatibleWith(type)) invalidRequest()
            val bytes = request.inputStream.readNBytes(65_537)
            if (bytes.size > 65_536) invalidRequest()
            val node = json.parse(bytes)
            if (!node.isObject || node.fieldNames().asSequence().toSet() != setOf("username", "password") ||
                !node["username"].isTextual || !node["password"].isTextual ||
                node["username"].textValue().isEmpty() || node["password"].textValue().isEmpty()) invalidRequest()
            val username = node["username"].textValue()
            return throttle.attempt(username, request.remoteAddr) {
                val result = authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, node["password"].textValue()))
                val identity = (result.principal as LoginUser).identity
                UsernamePasswordAuthenticationToken.authenticated(identity, null, identity.authorities())
            }
        } catch (error: ApiProblem) { errors.write(request, response, error); return null }
        catch (error: org.springframework.security.authentication.AuthenticationServiceException) {
            val cause = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<org.springframework.dao.DataAccessException>().firstOrNull()
            errors.write(request, response, errors.infrastructure(cause ?: error, request)); return null
        }
        catch (error: org.springframework.security.core.AuthenticationException) { throw error }
        catch (error: Exception) { errors.write(request, response, errors.infrastructure(error, request)); return null }
    }
}
