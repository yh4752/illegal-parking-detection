package kr.ac.sejong.parking.zone.adapter.http

import kr.ac.sejong.parking.auth.WebIdentity
import kr.ac.sejong.parking.zone.application.EventFilter
import kr.ac.sejong.parking.zone.application.ReadQueries
import org.springframework.context.annotation.Profile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*

@RestController
@Profile("web-api")
class ReadController(private val queries: ReadQueries) {
    @GetMapping("/api/csrf") fun csrf(token: CsrfToken) = mapOf("token" to token.token)
    @GetMapping("/api/auth/me") fun me(@AuthenticationPrincipal user: WebIdentity) = user.response()
    @GetMapping("/api/zones/{id}/status") fun zone(@PathVariable id: String, @AuthenticationPrincipal user: WebIdentity) = queries.zone(user.userId, id)
    @GetMapping("/api/events") fun events(@RequestParam params: MultiValueMap<String, String>, @AuthenticationPrincipal user: WebIdentity) = queries.events(user.userId, EventFilter.parse(params))
    @GetMapping("/api/events/{id}") fun detail(@PathVariable id: String, @AuthenticationPrincipal user: WebIdentity) = queries.detail(user.userId, id)
}
