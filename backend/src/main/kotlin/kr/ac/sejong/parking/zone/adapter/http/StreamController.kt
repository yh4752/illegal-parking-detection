package kr.ac.sejong.parking.zone.adapter.http

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kr.ac.sejong.parking.auth.WebIdentity
import kr.ac.sejong.parking.web.invalidRequest
import kr.ac.sejong.parking.web.validId
import kr.ac.sejong.parking.zone.application.ReadQueries
import kr.ac.sejong.parking.zone.adapter.sse.ZoneStreams
import org.springframework.context.annotation.Profile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*

@RestController
@Profile("web-api")
class StreamController(private val queries:ReadQueries,private val streams:ZoneStreams) {
    @GetMapping("/api/stream") fun stream(@RequestParam params:MultiValueMap<String,String>,@AuthenticationPrincipal user:WebIdentity,request:HttpServletRequest,response:HttpServletResponse) {
        if(params.keys!=setOf("zone_id") || params["zone_id"]?.size!=1) invalidRequest()
        val zone=validId(params.getFirst("zone_id")!!)
        val initial=queries.zone(user.userId,zone)
        streams.open(zone,user.userId,request.getSession(false),request,response,initial)
    }
}
