package kr.ac.sejong.parking

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import org.springframework.context.ApplicationContext
import kr.ac.sejong.parking.configuration.MqttLearningConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource
import kr.ac.sejong.parking.configuration.EventValidationConfiguration
import kr.ac.sejong.parking.configuration.EventIngestionConfiguration

@SpringBootTest
@AutoConfigureMockMvc
class BootstrapHttpTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val context: ApplicationContext,
) {
    @Test
    fun `기본 실행은 MQTT 학습 연결을 활성화하지 않는다`() {
        assertThat(context.getBeansOfType(MqttLearningConfiguration::class.java)).isEmpty()
        assertThat(context.getBeansOfType(DataSource::class.java)).isEmpty()
        assertThat(context.getBeansOfType(EventValidationConfiguration::class.java)).isEmpty()
        assertThat(context.getBeansOfType(EventIngestionConfiguration::class.java)).isEmpty()
    }

    @Test
    fun `기동 상태만 공개한다`() {
        mvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
            jsonPath("$.components") { doesNotExist() }
        }
    }

    @Test
    fun `미구현 제품 API를 공개하지 않는다`() {
        mvc.get("/api/zones/zone-01/status").andExpect { status { isForbidden() } }
    }

    @Test
    fun `관리 상세 정보를 공개하지 않는다`() {
        mvc.get("/actuator/env").andExpect { status { isForbidden() } }
    }
}
