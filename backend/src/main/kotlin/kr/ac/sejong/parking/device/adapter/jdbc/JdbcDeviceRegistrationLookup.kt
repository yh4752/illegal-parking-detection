package kr.ac.sejong.parking.device.adapter.jdbc

import kr.ac.sejong.parking.device.application.DeviceRegistrationLookup
import org.springframework.jdbc.core.JdbcTemplate

class JdbcDeviceRegistrationLookup(private val jdbc: JdbcTemplate) : DeviceRegistrationLookup {
    override fun isEnabled(deviceId: String, zoneId: String): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM device WHERE device_id = ? AND zone_id = ? AND enabled)",
            Boolean::class.java, deviceId, zoneId,
        ) == true
}
