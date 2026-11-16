package kr.ac.sejong.parking.configuration

import io.micrometer.core.instrument.MeterRegistry
import kr.ac.sejong.parking.operations.OperationalMetrics
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class OperationalMetricsConfiguration {
    @Bean fun operationalMetrics(registry: MeterRegistry) = OperationalMetrics(registry)
}
