package kr.ac.sejong.parking.zone.application

import com.fasterxml.jackson.databind.JsonNode
import kr.ac.sejong.parking.device.adapter.jdbc.RevisionReservations
import kr.ac.sejong.parking.web.ApiProblem
import kr.ac.sejong.parking.web.validId
import kr.ac.sejong.parking.zone.adapter.jdbc.JdbcReadStore
import kr.ac.sejong.parking.zone.adapter.jdbc.ReadResult
import java.time.Clock

class ReadQueries(private val store: JdbcReadStore, private val revisions: RevisionReservations, private val clock: Clock) {
    fun zone(user: String, zone: String) = checked(store.zone(user, validId(zone), clock.instant()))
    fun events(user: String, filter: EventFilter) = checked(store.events(user, filter, clock.instant()))
    fun detail(user: String, event: String) = checked(store.detail(user, validId(event), clock.instant()))
    private fun checked(result: ReadResult): JsonNode {
        try { revisions.checkReadable(result.zoneId, result.reservedThrough) }
        catch (_: Exception) { throw ApiProblem(503, "SERVICE_UNAVAILABLE", "구역 상태 확인이 필요합니다.") }
        return result.body
    }
}
