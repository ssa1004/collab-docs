package com.example.collab.application.port.out

import com.example.collab.domain.rag.DocumentChunk

/**
 * 문서 AI out port. 어댑터: 실 LLM/임베딩(prod, Spring AI) / offline 결정론 stub(dev).
 *
 * offline 모드는 "데모용 결정론 응답"임을 정직하게 표기한다(ADR-0001). 과장 금지.
 */
interface AiAssistPort {
    /** 텍스트 임베딩 벡터. 청크 유사도 검색용. */
    fun embed(text: String): FloatArray

    /** 주어진 컨텍스트 청크들을 근거로 질문에 답한다(RAG). */
    fun ask(question: String, contextChunks: List<DocumentChunk>): AiAnswer

    /** 텍스트 요약. */
    fun summarize(text: String): String
}

/**
 * @param answer 생성된 답변 텍스트.
 * @param citedOrdinals 답변 근거로 사용한 청크들의 ordinal(인용). 빈 리스트면 근거 없음/일반 답변.
 * @param offline 이 응답이 offline 결정론 모드 산출물인지(정직 표기).
 */
data class AiAnswer(
    val answer: String,
    val citedOrdinals: List<Int>,
    val offline: Boolean = false,
)
