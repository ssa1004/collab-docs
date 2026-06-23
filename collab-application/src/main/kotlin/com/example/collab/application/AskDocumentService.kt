package com.example.collab.application

import com.example.collab.application.authz.DocumentAuthorizer
import com.example.collab.application.port.out.AiAssistPort
import com.example.collab.application.port.out.DocumentRepository
import com.example.collab.application.port.out.DocumentSearchPort
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import com.example.collab.domain.rag.DocumentChunk
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 문서 기반 RAG 질의 use case (권한: view).
 *
 * 파이프라인:
 *  1) 문서 본문을 결정론적으로 청크 분할(DocumentChunk.chunk).
 *  2) 질문과 관련 높은 청크를 retrieve — DocumentSearchPort.searchChunks 키워드 검색.
 *  3) 검색 결과가 없으면(키워드 미스) AiAssistPort.embed 유사도로 fallback retrieve.
 *  4) 선택된 컨텍스트 청크들로 AiAssistPort.ask 호출 → 답변 + 인용 ordinal.
 *
 * 인용(citation)은 ask 가 돌려준 citedOrdinals 를 신뢰하되, 비어 있으면 retrieve 된 청크 ordinal 로 보강한다
 * (어떤 근거를 봤는지 항상 투명하게 노출).
 */
@Service
class AskDocumentService(
    private val documentRepository: DocumentRepository,
    private val searchPort: DocumentSearchPort,
    private val aiPort: AiAssistPort,
    private val authorizer: DocumentAuthorizer,
) {
    @Transactional(readOnly = true)
    fun ask(command: AskDocumentCommand): AskDocumentResult {
        authorizer.requireView(command.documentId, command.requesterId)
        val document = documentRepository.load(command.documentId)

        val chunks = DocumentChunk.chunk(command.documentId, document.content)
        if (chunks.isEmpty()) {
            val answer = aiPort.ask(command.question, emptyList())
            return AskDocumentResult(answer.answer, emptyList(), answer.offline)
        }

        // 2) 키워드 검색 retrieve
        var retrieved = searchPort.searchChunks(command.documentId, command.question, chunks, command.topK)
            .map { it.chunk }

        // 3) 키워드 미스 → 임베딩 유사도 fallback
        if (retrieved.isEmpty()) {
            retrieved = embeddingFallback(command.question, chunks, command.topK)
        }

        // 4) RAG ask
        val answer = aiPort.ask(command.question, retrieved)

        // 인용 ordinal: ask 가 준 것 우선, 없으면 retrieve 된 청크로 보강
        val cited = answer.citedOrdinals.ifEmpty { retrieved.map { it.ordinal } }
            .distinct().sorted()

        return AskDocumentResult(answer.answer, cited, answer.offline)
    }

    /** 질문 임베딩과 각 청크 임베딩의 코사인 유사도로 상위 topK 선택. */
    private fun embeddingFallback(question: String, chunks: List<DocumentChunk>, topK: Int): List<DocumentChunk> {
        val q = aiPort.embed(question)
        return chunks
            .map { it to cosine(q, aiPort.embed(it.text)) }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val n = minOf(a.size, b.size)
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in 0 until n) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (Math.sqrt(na) * Math.sqrt(nb))
    }
}

data class AskDocumentCommand(
    val documentId: DocumentId,
    val requesterId: UserId,
    val question: String,
    val topK: Int = 4,
)

/**
 * @param citedOrdinals 답변 근거 청크 ordinal(인용). 클라이언트가 본문에서 하이라이트할 수 있다.
 * @param offline offline 결정론 AI 모드 산출물 여부(정직 표기).
 */
data class AskDocumentResult(
    val answer: String,
    val citedOrdinals: List<Int>,
    val offline: Boolean,
)
