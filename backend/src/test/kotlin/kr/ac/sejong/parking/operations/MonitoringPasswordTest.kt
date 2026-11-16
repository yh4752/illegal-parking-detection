package kr.ac.sejong.parking.operations

import kr.ac.sejong.parking.configuration.monitoringPassword
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class MonitoringPasswordTest {
    @TempDir lateinit var directory:Path
    @Test fun `line endings cannot pad an empty or short management password`() {
        val file=directory.resolve("secret")
        for(value in listOf("", "x", " ".repeat(32))) {
            file.toFile().writeText(value+"\n".repeat(32))
            assertThatThrownBy { monitoringPassword(file,value) }.hasMessage("monitoring_secret_value_invalid")
        }
    }
    @Test fun `exact file secret is required without leaking its content`() {
        val file=directory.resolve("secret");val password="private-value-".repeat(4)
        file.toFile().writeText(password+"\n")
        assertThat(monitoringPassword(file,password)).isEqualTo(password)
        assertThatThrownBy { monitoringPassword(file,"different") }.hasMessage("monitoring_secret_source_mismatch")
    }
}
