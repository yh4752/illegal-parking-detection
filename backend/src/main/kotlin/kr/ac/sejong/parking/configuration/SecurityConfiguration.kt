package kr.ac.sejong.parking.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

/** 기동 확인만 공개한다. 제품 로그인·세션·CSRF·역할은 후속 구현 대상이다. */
@Configuration
class SecurityConfiguration {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http.authorizeHttpRequests { requests ->
            requests.requestMatchers("/actuator/health").permitAll()
            requests.anyRequest().denyAll()
        }
        return http.build()
    }
}
