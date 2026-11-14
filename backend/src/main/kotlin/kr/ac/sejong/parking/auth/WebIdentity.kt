package kr.ac.sejong.parking.auth

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import java.io.Serializable

/** Session principal deliberately excludes credentials and username. */
data class WebIdentity(val userId: String, val role: String) : Serializable {
    fun authorities() = listOf(SimpleGrantedAuthority("ROLE_$role"))
    fun response() = mapOf("user_id" to userId, "role" to role)
}
class LoginUser(val identity: WebIdentity, private val login: String, private val hash: String, private val active: Boolean) : UserDetails {
    override fun getUsername() = login
    override fun getPassword() = hash
    override fun getAuthorities() = identity.authorities()
    override fun isEnabled() = active
}
class JdbcWebUsers(private val jdbc: JdbcTemplate) : UserDetailsService {
    override fun loadUserByUsername(username: String): UserDetails {
        if ('\u0000' in username) throw UsernameNotFoundException("invalid_credentials")
        return jdbc.query(
        "SELECT user_id,username,password_hash,role,enabled FROM app_user WHERE username=?",
        { r, _ -> LoginUser(WebIdentity(r.getString("user_id"), r.getString("role")), r.getString("username"),
            r.getString("password_hash"), r.getBoolean("enabled")) }, username,
    ).singleOrNull() ?: throw UsernameNotFoundException("invalid_credentials")
    }

    fun activeIdentity(userId: String): WebIdentity? = jdbc.query(
        "SELECT user_id,role FROM app_user WHERE user_id=? AND enabled",
        { r, _ -> WebIdentity(r.getString("user_id"), r.getString("role")) }, userId,
    ).singleOrNull()
}
