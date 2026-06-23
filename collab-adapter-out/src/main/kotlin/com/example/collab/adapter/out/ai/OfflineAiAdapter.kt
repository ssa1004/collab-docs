package com.example.collab.adapter.out.ai

import com.example.collab.application.port.out.AiAnswer
import com.example.collab.application.port.out.AiAssistPort
import com.example.collab.domain.rag.DocumentChunk
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 기본(zero-infra) AI 어댑터: API 키 불필요한 **offline 결정론** 구현.
 *
 * 정직 표기(ADR-0001): 모든 답변은 LLM 이 아니라 추출(extractive) 알고리즘 산출물이며
 * AiAnswer.offline=true 로 표시하고 답변 텍스트에도 그 사실을 밝힌다. 과장 금지.
 *
 * 알고리즘:
 *  - embed: 토큰 bag-of-words 를 고정 차원(64)에 해시 버킷으로 모은 벡터(결정론).
 *  - summarize: 문장 분리 → (리드 문장) + (키워드 점수 상위 문장) 추출 요약.
 *  - ask: 컨텍스트 청크를 질문 키워드(+코사인) 로 재랭킹 → 상위 청크에서 가장 관련 높은
 *         문장을 뽑아 추출 답변을 만들고, 사용한 청크 ordinal 을 인용으로 돌려준다.
 *
 * collab.ai 가 없거나 'offline' 이면 이 빈이 활성화된다(matchIfMissing=true).
 */
@Component
@ConditionalOnProperty(name = ["collab.ai"], havingValue = "offline", matchIfMissing = true)
class OfflineAiAdapter : AiAssistPort {

    companion object {
        private const val DIM = 64
        private const val PREFIX = "[offline] 결정론 추출 모드(LLM 아님). "
        private val STOP = setOf(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "be",
            "this", "that", "it", "as", "by", "with", "at", "from", "was", "were",
            "은", "는", "이", "가", "을", "를", "에", "의", "와", "과", "do", "does", "what", "how", "why",
        )
    }

    override fun embed(text: String): FloatArray {
        val v = FloatArray(DIM)
        for (tok in tokens(text)) {
            v[Math.floorMod(tok.hashCode(), DIM)] += 1f
        }
        // L2 정규화로 코사인 안정화(빈 입력은 0 벡터 그대로).
        val norm = Math.sqrt(v.fold(0.0) { acc, f -> acc + f * f })
        if (norm > 0) for (i in v.indices) v[i] = (v[i] / norm).toFloat()
        return v
    }

    override fun summarize(text: String): String {
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) return PREFIX + "(요약할 본문이 없습니다.)"
        if (sentences.size <= 2) return PREFIX + sentences.joinToString(" ")

        val freq = termFreq(text)
        // 리드 문장은 항상 포함, 나머지는 키워드 점수로 상위 2개. 원문 순서를 보존해 가독성 유지.
        val leadIdx = 0
        val ranked = sentences.indices
            .filter { it != leadIdx }
            .sortedByDescending { sentenceScore(sentences[it], freq) }
            .take(2)
        val chosen = (listOf(leadIdx) + ranked).distinct().sorted()
        return PREFIX + chosen.joinToString(" ") { sentences[it].trim() }
    }

    override fun ask(question: String, contextChunks: List<DocumentChunk>): AiAnswer {
        if (contextChunks.isEmpty()) {
            return AiAnswer(
                answer = PREFIX + "주어진 문서에서 관련 근거를 찾지 못했습니다.",
                citedOrdinals = emptyList(),
                offline = true,
            )
        }
        val qTerms = tokens(question).toSet()
        val qVec = embed(question)

        // 청크 재랭킹: 키워드 겹침을 주 점수로, 동점은 코사인 유사도로 보조.
        val ranked = contextChunks
            .map { chunk ->
                val overlap = tokens(chunk.text).count { it in qTerms }.toDouble()
                val cos = cosine(qVec, embed(chunk.text))
                Triple(chunk, overlap, cos)
            }
            .sortedWith(compareByDescending<Triple<DocumentChunk, Double, Double>> { it.second }.thenByDescending { it.third })

        // 답변에 쓸 청크: 키워드/유사도 점수가 0 이상인 상위 N(최소 1개).
        val useful = ranked.filter { it.second > 0.0 || it.third > 0.0 }.ifEmpty { listOf(ranked.first()) }
            .take(3)

        val freqAll = termFreq(contextChunks.joinToString("\n") { it.text })
        val best = useful.first().first
        val bestSentence = splitSentences(best.text)
            .maxByOrNull { s -> tokens(s).count { it in qTerms } * 10 + sentenceScore(s, freqAll) }
            ?.trim()
            ?: best.text.take(200)

        val cited = useful.map { it.first.ordinal }.distinct().sorted()
        val answer = buildString {
            append(PREFIX)
            append("질문과 가장 관련 높은 근거를 추출했습니다: \"")
            append(bestSentence)
            append("\" (인용 청크 ordinal: ")
            append(cited.joinToString(", "))
            append(").")
        }
        return AiAnswer(answer = answer, citedOrdinals = cited, offline = true)
    }

    // ---- helpers ----------------------------------------------------------

    private fun tokens(s: String): List<String> =
        s.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() && it !in STOP }

    private fun termFreq(text: String): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (t in tokens(text)) m[t] = (m[t] ?: 0) + 1
        return m
    }

    private fun sentenceScore(sentence: String, freq: Map<String, Int>): Double =
        tokens(sentence).sumOf { (freq[it] ?: 0).toDouble() }

    private fun splitSentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?。!?])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val n = minOf(a.size, b.size)
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until n) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (Math.sqrt(na) * Math.sqrt(nb))
    }
}
