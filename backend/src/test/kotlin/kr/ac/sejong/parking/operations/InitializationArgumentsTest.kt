package kr.ac.sejong.parking.operations

import kr.ac.sejong.parkingtools.InitializationArguments
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test

class InitializationArgumentsTest {
    private val valid = arrayOf("--zone-id=zone-01", "--device-id=edge-01", "--policy-version=3", "--reason=신규 데모 준비")

    @Test fun `기본은 검사이며 apply를 명시해야 변경한다`() {
        assertThat(InitializationArguments.parse(valid).apply).isFalse()
        assertThat(InitializationArguments.parse(valid + "--apply").apply).isTrue()
    }
    @Test fun `강제 초기화 프로필 암호 등의 임의 옵션과 중복 옵션은 받지 않는다`() {
        for (extra in listOf("--force=true", "--spring.profiles.active=mqtt-ingest-local", "--password=secret", "--zone-id=zone-02")) {
            assertThatThrownBy { InitializationArguments.parse(valid + extra) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { InitializationArguments.parse(valid + arrayOf("--apply", "--apply")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { InitializationArguments.parse(valid.dropLast(1).toTypedArray()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DemoInitializationRequest("zone-01", "edge-01", 3, " ") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
