package kr.ac.sejong.parking.auth

import kr.ac.sejong.parking.web.ApiProblem
import org.springframework.security.core.AuthenticationException
import org.springframework.security.authentication.AuthenticationServiceException
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration

/** Single-server, bounded per account/address failures. No client-supplied forwarded IP is trusted. */
class LoginThrottle(private val clock: Clock, private val capacity: Int = 10_000) {
    private class Entry(var touched: Long) { var failures = 0; var blockedUntil = 0L; var users = 0 }
    private val entries = mutableMapOf<String, Entry>()
    private val ttl = Duration.ofMinutes(10).toMillis()
    fun <T> attempt(username: String, remoteAddress: String, action: () -> T): T {
        val bytes = (username + '\u0000' + remoteAddress).toByteArray(Charsets.UTF_8)
        val key = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val entry = synchronized(entries) {
            val now = clock.millis()
            entries.entries.removeIf { it.value.users == 0 && now - it.value.touched >= ttl }
            val selected = entries[key] ?: run {
                if (entries.size >= capacity) throw limited(60)
                Entry(now).also { entries[key] = it }
            }
            selected.users++; selected
        }
        try {
            return synchronized(entry) {
                val now = clock.millis()
                if (now < entry.blockedUntil) throw limited((entry.blockedUntil - now + 999) / 1000)
                if (entry.blockedUntil != 0L) { entry.blockedUntil = 0; entry.failures = 0 }
                entry.touched = now
                try {
                    action().also { entry.failures = 0 }
                } catch (error: AuthenticationException) {
                    if (error is AuthenticationServiceException) throw error
                    entry.failures++
                    if (entry.failures >= 5) entry.blockedUntil = clock.millis() + Duration.ofMinutes(5).toMillis()
                    throw error
                }
            }
        } finally { synchronized(entries) { entry.users-- } }
    }
    private fun limited(seconds: Long) = ApiProblem(429, "RATE_LIMITED", "잠시 후 로그인을 다시 시도해 주세요.", seconds.coerceAtLeast(1))
}
