package kr.ac.sejong.parking

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.runApplication

@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
class ParkingApplication

fun main(args: Array<String>) {
    runApplication<ParkingApplication>(*args)
}
