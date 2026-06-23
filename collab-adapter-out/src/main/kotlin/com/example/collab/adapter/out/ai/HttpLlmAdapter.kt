package com.example.collab.adapter.out.ai

import com.example.collab.application.port.out.AiAnswer
import com.example.collab.application.port.out.AiAssistPort
import com.example.collab.domain.rag.DocumentChunk
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

/**
 * prod AI 어댑터: OpenAI 호환 chat/embeddings 엔드포인트로의 얇은 WebClient 클라이언트.
 *
 * collab.ai=llm 일 때만 활성화된다(dev 는 절대 이 경로를 타지 않음 — API 키 불필요).
 * Spring AI 풀 통합은 무겁기에, 학습 목적상 OpenAI 호환 REST 를 직접 호출하는 최소 구현으로 둔다.
 *
 * 설정:
 *  - collab.ai.base-url   (기본 https://api.openai.com/v1)
 *  - collab.ai.api-key    (필수)
 *  - collab.ai.chat-model (기본 gpt-4o-mini)
 *  - collab.ai.embed-model(기본 text-embedding-3-small)
 */
@Component
@ConditionalOnProperty(name = ["collab.ai"], havingValue = "llm")
class HttpLlmAdapter(
    @Value("\${collab.ai.base-url:https://api.openai.com/v1}") private val baseUrl: String,
    @Value("\${collab.ai.api-key:}") private val apiKey: String,
    @Value("\${collab.ai.chat-model:gpt-4o-mini}") private val chatModel: String,
    @Value("\${collab.ai.embed-model:text-embedding-3-small}") private val embedModel: String,
    webClientBuilder: WebClient.Builder,
) : AiAssistPort {

    private val log = LoggerFactory.getLogger(javaClass)
    private val http: WebClient = webClientBuilder
        .baseUrl(baseUrl)
        .defaultHeader("Authorization", "Bearer $apiKey")
        .build()

    init {
        require(apiKey.isNotBlank()) { "collab.ai=llm requires collab.ai.api-key" }
        log.info("HttpLlmAdapter active (collab.ai=llm, base-url=$baseUrl, chat=$chatModel).")
    }

    override fun embed(text: String): FloatArray {
        val body = mapOf("model" to embedModel, "input" to text)
        val resp = http.post().uri("/embeddings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .block() ?: return FloatArray(0)
        val arr = resp.path("data").firstOrNull()?.path("embedding") ?: return FloatArray(0)
        return FloatArray(arr.size()) { arr[it].floatValue() }
    }

    override fun summarize(text: String): String {
        val prompt = "Summarize the following document in 3 sentences:\n\n$text"
        return chat(prompt)
    }

    override fun ask(question: String, contextChunks: List<DocumentChunk>): AiAnswer {
        val context = contextChunks.joinToString("\n\n") { "[#${it.ordinal}] ${it.text}" }
        val prompt = buildString {
            append("Answer the question using ONLY the context. Cite chunk ordinals like [#n].\n\n")
            append("Context:\n").append(context).append("\n\nQuestion: ").append(question)
        }
        val answer = chat(prompt)
        // 모델이 본문에 박은 [#n] 인용을 파싱해 ordinal 로 환원.
        val cited = Regex("\\[#(\\d+)]").findAll(answer)
            .map { it.groupValues[1].toInt() }.distinct().sorted().toList()
        return AiAnswer(answer = answer, citedOrdinals = cited, offline = false)
    }

    private fun chat(prompt: String): String {
        val body = mapOf(
            "model" to chatModel,
            "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
            "temperature" to 0,
        )
        val resp = http.post().uri("/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .block() ?: return ""
        return resp.path("choices").firstOrNull()?.path("message")?.path("content")?.asText().orEmpty()
    }
}
