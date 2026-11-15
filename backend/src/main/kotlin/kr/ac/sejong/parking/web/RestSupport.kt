package kr.ac.sejong.parking.web

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.dao.DataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

class ApiProblem(val status: Int, val code: String, val safeMessage: String, val retryAfter: Long? = null) : RuntimeException(code)
fun invalidRequest(): Nothing = throw ApiProblem(400, "INVALID_SCHEMA", "요청 형식을 확인해 주세요.")
fun validId(id: String): String {
    if (id.isEmpty() || id.codePointCount(0, id.length) > 128 || '\u0000' in id) invalidRequest()
    return id
}

class RestErrors(private val mapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)
    fun body(request: HttpServletRequest, problem: ApiProblem): Map<String, Any> = mapOf(
        "code" to problem.code, "message" to problem.safeMessage, "details" to emptyMap<String, Any>(),
        "retryable" to (problem.status in setOf(429, 503)), "request_id" to requestId(request),
    )
    fun requestId(request: HttpServletRequest): String = (request.getAttribute("parking.request_id") as? String)
        ?: UUID.randomUUID().toString().also { request.setAttribute("parking.request_id", it) }
    fun write(request: HttpServletRequest, response: HttpServletResponse, problem: ApiProblem) {
        response.status = problem.status; response.contentType = "application/json"; response.characterEncoding = "UTF-8"
        response.setHeader("Cache-Control", "no-store"); response.setHeader("X-Request-ID", requestId(request))
        problem.retryAfter?.let { response.setHeader("Retry-After", it.toString()) }
        mapper.writeValue(response.outputStream, body(request, problem))
    }
    fun infrastructure(error: Exception, request: HttpServletRequest): ApiProblem {
        log.warn("REST_FAILURE request_id={} category={}", requestId(request), error.javaClass.simpleName)
        return if (error is DataAccessException || error is org.springframework.transaction.CannotCreateTransactionException) ApiProblem(503, "SERVICE_UNAVAILABLE", "잠시 후 다시 시도해 주세요.")
        else ApiProblem(500, "INTERNAL_ERROR", "요청을 처리하지 못했습니다.")
    }
}

/** Only the request parser uses strict duplicate detection; storage's mapper is unchanged. */
class StrictJson(mapper: ObjectMapper) {
    private val strict = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    fun parse(bytes: ByteArray): JsonNode = try {
        strict.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .readTree(bytes) ?: invalidRequest()
    } catch (_: com.fasterxml.jackson.core.JsonProcessingException) { invalidRequest() }
}

@RestControllerAdvice
@Profile("web-api")
class RestExceptionAdvice(private val errors: RestErrors) {
    @ExceptionHandler(ApiProblem::class)
    fun problem(error: ApiProblem, request: HttpServletRequest): ResponseEntity<*> {
        val result = ResponseEntity.status(error.status).header("Cache-Control", "no-store")
            .header("X-Request-ID", errors.requestId(request))
        error.retryAfter?.let { result.header("Retry-After", it.toString()) }
        return result.body(errors.body(request, error))
    }
    @ExceptionHandler(Exception::class)
    fun unexpected(error: Exception, request: HttpServletRequest): ResponseEntity<*> =
        problem(errors.infrastructure(error, request), request)
}
