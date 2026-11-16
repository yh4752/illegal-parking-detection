package kr.ac.sejong.parking.device.adapter.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.core.env.Environment
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

data class RevisionHead(val version: Long, val sha256: String, val document: ObjectNode)

/** Internal LOCAL_ONLY reference adapter. An external provider needs its own authenticated TLS adapter. */
class RevisionLedger(private val url: URI, private val storeId: String, tokenFile: Path, private val mapper: ObjectMapper) {
    private val token = Files.readString(tokenFile).trim().also { require(it.matches(Regex("[A-Za-z0-9_-]{32,128}"))) }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    init {
        require(url.scheme == "http" && url.host in setOf("revision-ledger", "127.0.0.1", "localhost") &&
            url.userInfo == null && url.query == null && url.fragment == null && url.path in setOf("", "/")) {
            "local_revision_ledger_url_required"
        }
        java.util.UUID.fromString(storeId)
    }

    fun head(): RevisionHead = request("GET", "/v1/head", null)

    fun compareAndSet(expected: RevisionHead, next: ObjectNode): RevisionHead {
        val body = mapper.createObjectNode().put("expected_version", expected.version).put("expected_sha256", expected.sha256)
        body.set<ObjectNode>("document", next)
        return request("POST", "/v1/versions", mapper.writeValueAsString(body)).also {
            check(it.version == expected.version + 1 && it.document == mapper.readTree(mapper.writeValueAsBytes(next))) { "revision_ledger_write_unconfirmed" }
        }
    }

    fun requireDocument(expected: ObjectNode): RevisionHead = head().also {
        check(it.document == mapper.readTree(mapper.writeValueAsBytes(expected))) { "revision_ledger_mismatch" }
    }

    private fun request(method: String, path: String, body: String?): RevisionHead {
        try {
            val request = HttpRequest.newBuilder(url.resolve(path)).timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val bytes = response.body().use { it.readNBytes(1_048_577) }
            check(response.statusCode() in setOf(200, 201) && bytes.size <= 1_048_576) { "revision_ledger_request_rejected" }
            val json = mapper.readTree(bytes)
            check(json.path("store_id").asText() == storeId && json.path("version").canConvertToLong() &&
                json.path("version").longValue() >= 0 && json.path("sha256").asText().matches(Regex("[a-f0-9]{64}")) &&
                json.path("document").isObject && json["document"].path("format_version").asInt() == 1 &&
                json["document"].path("zones").isObject) { "revision_ledger_response_invalid" }
            return RevisionHead(json["version"].longValue(), json["sha256"].asText(), json["document"] as ObjectNode)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt(); throw IllegalStateException("revision_ledger_unavailable")
        } catch (_: Exception) {
            // Never emit credentials, remote bodies or user-controlled document contents.
            throw IllegalStateException("revision_ledger_unavailable_or_invalid")
        }
    }

    companion object {
        fun configured(env: Environment, mapper: ObjectMapper): RevisionLedger? {
            val enabled = env.getProperty("PARKING_REVISION_LEDGER_REQUIRED", Boolean::class.java, false)
            if (!enabled) return null
            check(env.getRequiredProperty("PARKING_REVISION_LEDGER_SCOPE") == "LOCAL_ONLY")
            return RevisionLedger(URI(env.getRequiredProperty("PARKING_REVISION_LEDGER_URL")),
                env.getRequiredProperty("PARKING_REVISION_LEDGER_ID"),
                Path.of(env.getRequiredProperty("PARKING_REVISION_LEDGER_TOKEN_FILE")), mapper)
        }
    }
}
