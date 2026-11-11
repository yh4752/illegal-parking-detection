package kr.ac.sejong.parking

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
class BootstrapHttpTest(@Autowired private val mvc: MockMvc) {
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
