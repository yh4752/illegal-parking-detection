package kr.ac.sejong.parking.device.application

import com.fasterxml.jackson.databind.JsonNode

/** V04–V12 in docs/design/event-ingest-v1.md. Call only after Schema and V03 pass. */
internal object EventMessageSemantics {
    private val terminalStates = setOf("CLOSED_EXIT", "CLOSED_MANUAL")
    private val overdueStates = terminalStates + setOf("HELD", "LONG_UNRESOLVED")
    private val commonSummaryFields = listOf(
        "event_policy_version", "guidance_count", "continuous_stop_ms", "body_type", "use_type", "overdue",
    )

    fun validate(message: JsonNode): EventValidationResult.Invalid? {
        val snapshot = message["snapshot"]
        val event = message["event"]
        val hasCurrentEvent = !snapshot["current_event_id"].isNull
        val clockUntrustedReason = snapshot["reason_codes"].any { it.asText() == "CLOCK_UNTRUSTED" }

        if (!snapshot["clock_synced"].booleanValue()) {
            if (snapshot["continuous_stop_ms"].decimalValue().signum() != 0) {
                return invalid("V04", "clock_untrusted_stop_time")
            }
            if (!clockUntrustedReason) return invalid("V04", "clock_untrusted_reason")
        }

        val pauses = snapshot["pauses"]
        if (pauses.map { it["pause_id"].asText() }.toSet().size != pauses.size()) {
            return invalid("V05", "duplicate_pause_id")
        }
        val expectedMode = when {
            pauses.any { it["status"].asText() == "ACTIVE" } -> "PAUSED"
            pauses.any { it["status"].asText() == "ACK_REQUIRED" } -> "AWAITING_RESUME"
            else -> "AUTO" // SCHEDULED is not yet an active pause.
        }
        if (snapshot["mode"].asText() != expectedMode) return invalid("V05", "pause_mode")

        val activePolicy = snapshot["active_policy_version"]
        val receivedPolicy = snapshot["received_policy_version"]
        if (activePolicy.isNull) {
            if (hasCurrentEvent || snapshot["camera_health"].asText() != "RECONFIGURE") {
                return invalid("V06", "inactive_policy_snapshot")
            }
        } else {
            if (receivedPolicy.isNull) return invalid("V06", "active_policy_without_received")
            if (receivedPolicy.decimalValue() < activePolicy.decimalValue()) {
                return invalid("V07", "policy_version_order")
            }
        }

        // Evaluate event and snapshot independently: they can describe different incidents.
        val summaries = buildList {
            if (!event.isNull) add(event to "state")
            if (hasCurrentEvent) add(snapshot to "incident_state")
        }
        for ((summary, stateField) in summaries) {
            val state = summary[stateField].asText()
            val count = summary["guidance_count"].intValue()
            if ((state in setOf("OBSERVING", "STOP_CONFIRMED") && count != 0) ||
                (state in setOf("GUIDING", "GUIDED") && count !in 1..2)
            ) return invalid("V08", "incident_guidance_count")
            if (state == "LONG_UNRESOLVED" && !summary["overdue"].booleanValue()) {
                return invalid("V08", "incident_overdue_flag")
            }
        }
        for ((summary, stateField) in summaries) {
            if (summary["overdue"].booleanValue() && summary[stateField].asText() !in overdueStates) {
                return invalid("V09", "overdue_state")
            }
        }

        val expectedClosedState = when (message["event_type"].asText()) {
            "EVENT_CLOSED_EXIT" -> "CLOSED_EXIT"
            "EVENT_CLOSED_MANUAL" -> "CLOSED_MANUAL"
            else -> null
        }
        if (expectedClosedState != null && (event.isNull || event["state"].asText() != expectedClosedState)) {
            return invalid("V10", "closure_event_state")
        }

        if (!event.isNull && event["event_id"] == snapshot["current_event_id"]) {
            if (event["state"] != snapshot["incident_state"]) return invalid("V11", "same_event_state")
            for (field in commonSummaryFields) {
                if (!sameValue(event[field], snapshot[field])) return invalid("V11", "same_event_$field")
            }
        }

        val requiresHold = snapshot["mode"].asText() != "AUTO" ||
            snapshot["camera_health"].asText() != "OK" ||
            !snapshot["clock_synced"].booleanValue() || clockUntrustedReason ||
            snapshot["output_status"].asText() in setOf("FAILED", "UNKNOWN")
        if (hasCurrentEvent && requiresHold &&
            snapshot["incident_state"].asText() !in terminalStates + "HELD"
        ) return invalid("V12", "current_incident_requires_hold")

        // Do not infer legality, elapsed stop time, physical output, or DB state here.
        return null
    }

    private fun sameValue(left: JsonNode, right: JsonNode): Boolean =
        if (left.isNumber && right.isNumber) left.decimalValue().compareTo(right.decimalValue()) == 0
        else left == right

    private fun invalid(ruleId: String, reason: String) = EventValidationResult.Invalid(reason, ruleId)
}
