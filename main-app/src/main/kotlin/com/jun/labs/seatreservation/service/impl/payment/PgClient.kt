package com.jun.labs.seatreservation.service.impl.payment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jun.labs.seatreservation.service.PaymentProperties
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.http.HttpClient
import java.time.Instant

/**
 * ADR-017 흉내 PG 클라이언트(설계-3). 응답을 분류표의 세 갈래로 돌려준다:
 * [Call.Ok] 200(상태) · [Call.Rejected] 4xx(코드 · 상태 — 처리된 거부) · [Call.Unavailable] 5xx · 연결 실패 · 타임아웃(결과 모름일 수 있다).
 * PG 주소가 없으면 만들지 않는다([PaymentProperties.pgEnabled] — 호출자가 먼저 본다).
 */
@Component
class PgClient(private val props: PaymentProperties, private val mapper: ObjectMapper) {

    sealed interface Call {
        data class Ok(val state: String) : Call
        data class Rejected(val status: Int, val code: String?, val state: String?) : Call
        data class Unavailable(val reason: String) : Call
    }

    private val client: RestClient? by lazy {
        val base = props.pg.baseUrl?.takeIf { it.isNotBlank() } ?: return@lazy null
        val http = HttpClient.newBuilder().connectTimeout(props.pg.connectTimeout).build()
        val factory = JdkClientHttpRequestFactory(http).apply { setReadTimeout(props.pg.readTimeout) }
        RestClient.builder().baseUrl(base).requestFactory(factory).build()
    }

    fun register(uid: String, userId: Long, amount: Long, deadline: Instant): Call =
        post("/intents", mapOf("uid" to uid, "userId" to userId, "amount" to amount, "deadline" to deadline.toString()))

    fun close(uid: String): Call = post("/payments/$uid/close", null)

    fun capture(uid: String): Call = post("/payments/$uid/capture", null)

    /** 지금 상태 조회(결과 모름 확인) — 없으면 Rejected(404) */
    fun get(uid: String): Call = exchange { c ->
        c.get().uri("/payments/{uid}", uid).exchange<Call> { _, res -> read(res.statusCode, res.body.readAllBytes(), wrapped = true) }!!
    }

    private fun post(path: String, body: Any?): Call = exchange { c ->
        val spec = c.post().uri(path).contentType(MediaType.APPLICATION_JSON)
        (if (body != null) spec.body(body) else spec).exchange<Call> { _, res -> read(res.statusCode, res.body.readAllBytes(), wrapped = true) }!!
    }

    private fun exchange(block: (RestClient) -> Call): Call {
        val c = client ?: return Call.Unavailable("pg-disabled")
        return try {
            block(c)
        } catch (e: RestClientException) {
            Call.Unavailable(e.javaClass.simpleName)
        }
    }

    /** 본문 `{payment, code}`(변경 · 조회 공통 — 명세 부록 A) */
    private fun read(status: HttpStatusCode, bytes: ByteArray, wrapped: Boolean): Call {
        if (status.is5xxServerError) return Call.Unavailable("http-${status.value()}")
        val node: JsonNode? = if (bytes.isEmpty()) null else runCatching { mapper.readTree(bytes) }.getOrNull()
        val payment = if (wrapped) node?.get("payment") else node
        val state = payment?.get("state")?.takeIf { !it.isNull }?.asText()
        if (status.is2xxSuccessful && state != null) return Call.Ok(state)
        if (status.is4xxClientError) return Call.Rejected(status.value(), node?.get("code")?.takeIf { !it.isNull }?.asText(), state)
        return Call.Unavailable("http-${status.value()}")
    }
}
