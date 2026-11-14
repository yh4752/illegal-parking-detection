package kr.ac.sejong.parking.web

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ac.sejong.parking.auth.*
import kr.ac.sejong.parking.zone.application.*
import kr.ac.sejong.parkingtools.AccountArguments
import kr.ac.sejong.parkingtools.readAccountPassword
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.util.LinkedMultiValueMap
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class WebTestClock(var now: Instant = Instant.parse("2026-11-14T00:00:00Z")) : Clock() {
    override fun instant() = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    fun advance(duration: Duration) { now = now.plus(duration) }
}
class WebPolicyTest {
    private val mapper = ObjectMapper()
    private fun params(vararg pairs: Pair<String,String>) = LinkedMultiValueMap<String,String>().apply { pairs.forEach { add(it.first,it.second) } }
    @Test fun `프로필 그룹을 펼쳐도 로컬 HTTP만 Secure 쿠키를 해제한다`() {
        for ((profile,secure) in listOf("app-local" to false,"web-api" to true,"app-local,mqtt-ingest-local" to false)) {
            org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withInitializer(org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=$profile")
                .run { context ->
                    assertThat(context.environment.activeProfiles).contains("web-api","database")
                    assertThat(context.environment.getProperty("server.servlet.session.cookie.secure",Boolean::class.java))
                        .describedAs(profile).isEqualTo(secure)
                }
        }
    }
    @Test fun `cursor는 필터와 위치를 서명하고 변조 및 다른 구역을 거부한다`() {
        val codec = EventCursor(mapper, StrictJson(mapper), ByteArray(32) { 31 })
        val filter = EventFilter.parse(params("zone_id" to "zone-01"))
        val pos = CursorPosition(Instant.parse("2026-11-14T00:00:00.123456Z"), "event-가")
        val signed = codec.encode(filter,pos)
        assertThat(codec.decode(filter.copy(cursor=signed))).isEqualTo(pos)
        for (bad in listOf(signed.replaceRange(2,3,if(signed[2]=='a') "b" else "a"),"invalid","a".repeat(2049)))
            assertThatThrownBy { codec.decode(filter.copy(cursor=bad)) }.isInstanceOf(ApiProblem::class.java)
        assertThatThrownBy { codec.decode(filter.copy(zone="zone-02",cursor=signed)) }.isInstanceOf(ApiProblem::class.java)
        assertThatThrownBy { codec.decode(filter.copy(state="GUIDED",cursor=signed)) }.isInstanceOf(ApiProblem::class.java)
    }
    @Test fun `트랜잭션 연결 실패는 503 예상하지 못한 오류는 500이며 내부 원문은 반환하지 않는다`() {
        val errors=RestErrors(mapper)
        val request=org.springframework.mock.web.MockHttpServletRequest()
        val connection=errors.infrastructure(org.springframework.transaction.CannotCreateTransactionException("private-db",java.sql.SQLException("password secret")),request)
        assertThat(connection.status).isEqualTo(503)
        assertThat(errors.body(request,connection).toString()).doesNotContain("private-db","password secret")
        assertThat(errors.infrastructure(IllegalStateException("private-internal"),request).code).isEqualTo("INTERNAL_ERROR")
    }
    @Test fun `필터는 중복 오타 정수가 아닌 limit과 UTC 아닌 시각을 거부한다`() {
        val invalid = listOf(params("zone_id" to "z","limit" to "0"), params("zone_id" to "z","limit" to "101"),
            params("zone_id" to "z","limit" to "1.0"),params("zone_id" to "z","state" to "BAD"),
            params("zone_id" to "z","zone_id" to "x"),params("zone_id" to "z","other" to "x"),
            params("zone_id" to "z","from" to "2026-11-14T09:00:00+09:00"),
            params("zone_id" to "z","from" to "2026-11-14T00:00:00Z","to" to "2026-11-14T00:00:00Z"))
        invalid.forEach { assertThatThrownBy { EventFilter.parse(it) }.isInstanceOf(ApiProblem::class.java) }
    }
    @Test fun `로그인 다섯번 실패 후 같은 계정 주소를 오분 제한하며 다른 주소는 독립이다`() {
        val clock=WebTestClock();val limiter=LoginThrottle(clock)
        repeat(5) { assertThatThrownBy { limiter.attempt("a","127.0.0.1") { throw BadCredentialsException("bad") } }.isInstanceOf(BadCredentialsException::class.java) }
        assertThatThrownBy { limiter.attempt("a","127.0.0.1") { true } }.isInstanceOfSatisfying(ApiProblem::class.java) { assertThat(it.retryAfter).isEqualTo(300L) }
        assertThat(limiter.attempt("a","127.0.0.2") { true }).isTrue()
        clock.advance(Duration.ofMinutes(5)); assertThat(limiter.attempt("a","127.0.0.1") { true }).isTrue()
    }
    @Test fun `인증 저장소 장애는 비밀번호 오류 횟수에 포함하지 않는다`() {
        val limiter=LoginThrottle(WebTestClock())
        repeat(6) { assertThatThrownBy { limiter.attempt("a","ip") {
            throw org.springframework.security.authentication.AuthenticationServiceException("database unavailable")
        } }.isInstanceOf(org.springframework.security.authentication.AuthenticationServiceException::class.java) }
        assertThat(limiter.attempt("a","ip") { true }).isTrue()
    }
    @Test fun `제한 저장소는 최대 크기에 도달하면 차단 기록을 버리지 않으며 TTL 후 다시 허용한다`() {
        val clock=WebTestClock();val limiter=LoginThrottle(clock,2)
        limiter.attempt("a","ip") { true }; limiter.attempt("b","ip") { true }
        assertThatThrownBy { limiter.attempt("c","ip") { true } }.isInstanceOf(ApiProblem::class.java)
        clock.advance(Duration.ofMinutes(10)); assertThat(limiter.attempt("c","ip") { true }).isTrue()
    }
    @Test fun `세션 idle은 정확히 삼십분 absolute는 정확히 여덟시간에 만료된다`() {
        val clock=WebTestClock();val lifetime=SessionLifetime(clock);val session=MockHttpSession()
        lifetime.start(session);clock.advance(Duration.ofMinutes(30).minusMillis(1));assertThat(lifetime.valid(session)).isTrue()
        clock.advance(Duration.ofMillis(1));assertThat(lifetime.valid(session)).isFalse()
        lifetime.start(session)
        repeat(16) { clock.advance(Duration.ofMinutes(29));lifetime.touch(session) }
        clock.advance(Duration.ofMinutes(16));assertThat(lifetime.valid(session)).isFalse()
    }
    @Test fun `계정 CLI는 비밀번호 인자와 임의 프로필을 허용하지 않는다`() {
        val args=arrayOf("create-user","--username=test","--role=VIEWER","--zone-id=z")
        assertThat(AccountArguments.parse(args).operation).isEqualTo("create-user")
        for (extra in listOf("--password=unsafe","--force=true","--spring.profiles.active=web-api"))
            assertThatThrownBy { AccountArguments.parse(args+extra) }.isInstanceOf(IllegalArgumentException::class.java)
    }
    @Test fun `계정 secret 파일의 공유 권한과 잘린 bcrypt 비밀번호를 거부한다`(@TempDir dir:Path) {
        val file=dir.resolve("secret");Files.writeString(file,"test-password-only\n")
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"))
        assertThatThrownBy { readAccountPassword(file.toString()) }.isInstanceOf(IllegalArgumentException::class.java)
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"))
        assertThat(String(readAccountPassword(file.toString()))).isEqualTo("test-password-only")
        Files.writeString(file,"한".repeat(25));assertThatThrownBy { readAccountPassword(file.toString()) }.isInstanceOf(IllegalStateException::class.java)
    }
}
