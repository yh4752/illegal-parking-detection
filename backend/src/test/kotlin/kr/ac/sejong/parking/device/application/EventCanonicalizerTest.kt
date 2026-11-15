package kr.ac.sejong.parking.device.application

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test

class EventCanonicalizerTest {
    private val mapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    private val canonicalizer = EventCanonicalizer(mapper)

    @Test fun `객체 순서 공백과 동일한 수 표기는 동일성에 영향이 없다`() {
        assertThat(canonicalizer.hash(mapper.readTree("""{ "b": [2.0, "한글"], "a": 1e3 }""")))
            .isEqualTo(canonicalizer.hash(mapper.readTree("""{"a":1000,"b":[2,"한글"]}""")))
    }
    @Test fun `배열 순서나 값이 달라지면 다른 hash다`() {
        assertThat(canonicalizer.hash(mapper.readTree("[1,2]"))).isNotEqualTo(canonicalizer.hash(mapper.readTree("[2,1]")))
    }
    @Test fun `반올림으로 정상 정수와 충돌할 수 있는 입력을 거부한다`() {
        assertThatThrownBy { canonicalizer.hash(mapper.readTree("{\"value\":9007199254740993}")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { canonicalizer.hash(mapper.readTree("{\"value\":10.0000000000000000001}")) }.isInstanceOf(IllegalArgumentException::class.java)
    }
    @Test fun `짝 없는 유니코드 surrogate를 거부한다`() {
        assertThatThrownBy { canonicalizer.hash(mapper.readTree("\"\\ud800\"")) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
