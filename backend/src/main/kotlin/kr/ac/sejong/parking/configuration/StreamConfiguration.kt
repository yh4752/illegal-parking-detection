package kr.ac.sejong.parking.configuration

import jakarta.servlet.http.HttpSessionEvent
import jakarta.servlet.http.HttpSessionListener
import kr.ac.sejong.parking.auth.*
import kr.ac.sejong.parking.zone.application.ReadQueries
import kr.ac.sejong.parking.zone.adapter.sse.ZoneStreams
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean
import org.springframework.context.annotation.*
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@Profile("web-api")
@EnableScheduling
class StreamConfiguration {
    @Bean(destroyMethod="close") fun zoneStreams(queries:ReadQueries,users:JdbcWebUsers,lifetime:SessionLifetime)=ZoneStreams(queries,users,lifetime)
    @Bean fun streamSessionListener(streams:ZoneStreams)=ServletListenerRegistrationBean(object:HttpSessionListener {
        override fun sessionDestroyed(event:HttpSessionEvent){streams.closeSession(event.session.id)}
    })
}
