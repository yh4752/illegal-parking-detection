package kr.ac.sejong.parkingtools

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ac.sejong.parking.configuration.webPasswordEncoder
import kr.ac.sejong.parking.web.validId
import org.flywaydb.core.Flyway
import org.springframework.boot.Banner
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import javax.sql.DataSource

data class AccountArguments(val operation: String, val values: Map<String, String>) {
    companion object {
        fun parse(args: Array<String>): AccountArguments {
            require(args.isNotEmpty()) { "operation_required" }
            val required = when (args[0]) {
                "create-user" -> setOf("username", "role", "zone-id")
                "grant-zone", "revoke-zone" -> setOf("user-id", "zone-id")
                "disable-user" -> setOf("user-id")
                else -> error("unknown_operation")
            }
            val values = mutableMapOf<String, String>()
            for (arg in args.drop(1)) {
                require(arg.startsWith("--") && '=' in arg) { "named_arguments_required" }
                val (key, value) = arg.substring(2).split('=', limit = 2)
                require(key in required && key !in values) { "unknown_or_duplicate_argument" }
                validId(value); require(value.isNotBlank()) { "blank_argument" }; values[key] = value
            }
            require(values.keys == required) { "missing_argument" }
            require(values["role"] == null || values["role"] in setOf("VIEWER", "OPERATOR")) { "invalid_role" }
            return AccountArguments(args[0], values)
        }
    }
}

/** Password path is supplied through an environment variable, never a CLI argument. */
fun readAccountPassword(file: String?): CharArray {
    val chars = if (file != null) {
        val path = Path.of(file)
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 1024) { "invalid_secret_file" }
        require(Files.getOwner(path).name == System.getProperty("user.name")) { "secret_file_owner_mismatch" }
        val forbidden = setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)
        require(Files.getPosixFilePermissions(path).none { it in forbidden }) { "secret_file_requires_owner_only_permissions" }
        Files.readString(path).removeSuffix("\n").removeSuffix("\r").toCharArray()
    } else {
        val console = checkNotNull(System.console()) { "interactive_console_or_PARKING_WEB_PASSWORD_FILE_required" }
        val first = checkNotNull(console.readPassword("Password: "))
        val second = checkNotNull(console.readPassword("Confirm password: "))
        try { require(first.contentEquals(second)) { "passwords_do_not_match" } }
        catch (error: Exception) { first.fill('\u0000'); throw error }
        finally { second.fill('\u0000') }
        first
    }
    if (chars.size < 12 || String(chars).toByteArray(Charsets.UTF_8).size > 72 || chars.any { it == '\n' || it == '\r' || it == '\u0000' }) {
        chars.fill('\u0000'); error("password_requires_12_characters_and_at_most_72_UTF8_bytes_without_newline")
    }
    return chars
}

fun main(args: Array<String>) {
    val options = AccountArguments.parse(args)
    val hash = if (options.operation == "create-user") {
        val password = readAccountPassword(System.getenv("PARKING_WEB_PASSWORD_FILE"))
        try { webPasswordEncoder().encode(String(password)) } finally { password.fill('\u0000') }
    } else null
    val app = SpringApplication(DemoInitializationContext::class.java).apply {
        webApplicationType = WebApplicationType.NONE; setBannerMode(Banner.Mode.OFF)
    }
    app.run("--spring.main.web-application-type=none", "--spring.profiles.active=database", "--spring.flyway.enabled=false").use { context ->
        val jdbc = context.getBean(JdbcTemplate::class.java)
        val flyway = Flyway.configure().dataSource(context.getBean(DataSource::class.java))
            .defaultSchema(jdbc.queryForObject("SELECT current_schema()", String::class.java)).load()
        flyway.validate(); check(flyway.info().pending().isEmpty()) { "database_migrations_required" }
        val mapper = context.getBean(ObjectMapper::class.java)
        val tx = TransactionTemplate(context.getBean(PlatformTransactionManager::class.java))
        val user = options.values["user-id"] ?: UUID.randomUUID().toString()
        tx.executeWithoutResult {
            val zone = options.values["zone-id"]
            if (zone != null) check(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM zone WHERE zone_id=?)", Boolean::class.java, zone) == true) { "zone_missing" }
            if (options.operation != "create-user") check(jdbc.queryForList("SELECT user_id FROM app_user WHERE user_id=? FOR UPDATE", user).size == 1) { "user_missing" }
            when (options.operation) {
                "create-user" -> {
                    jdbc.update("INSERT INTO app_user(user_id,username,password_hash,role) VALUES (?,?,?,?)", user, options.values["username"], hash, options.values["role"])
                    jdbc.update("INSERT INTO user_zone_access(user_id,zone_id) VALUES (?,?)", user, zone)
                }
                "grant-zone" -> jdbc.update("INSERT INTO user_zone_access(user_id,zone_id) VALUES (?,?) ON CONFLICT DO NOTHING", user, zone)
                "revoke-zone" -> jdbc.update("DELETE FROM user_zone_access WHERE user_id=? AND zone_id=?", user, zone)
                "disable-user" -> jdbc.update("UPDATE app_user SET enabled=false WHERE user_id=?", user)
            }
            jdbc.update("INSERT INTO audit_entry(audit_id,zone_id,action,related_id,detail,created_at) VALUES (?,?,?,?,?::jsonb,now())",
                UUID.randomUUID().toString(), zone, "WEB_ACCOUNT_" + options.operation.uppercase().replace('-', '_'), user,
                mapper.writeValueAsString(mapOf("source" to "local_operator_cli", "operation" to options.operation)))
        }
        println(mapper.writeValueAsString(mapOf("result" to "APPLIED", "operation" to options.operation, "user_id" to user)))
    }
}
