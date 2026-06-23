package com.example.collab.adapter.`in`.web

import com.example.collab.adapter.`in`.web.dto.AskRequest
import com.example.collab.adapter.`in`.web.dto.AskResponse
import com.example.collab.adapter.`in`.web.dto.SummarizeResponse
import com.example.collab.application.AskDocumentCommand
import com.example.collab.application.AskDocumentService
import com.example.collab.application.SummarizeDocumentService
import com.example.collab.domain.document.DocumentId
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 문서 AI REST 컨트롤러: RAG 질의(/ask) + 요약(/summarize).
 *
 * 기본(zero-infra) 프로필에서는 OfflineAiAdapter 가 결정론 추출 답변을 주고 offline=true 로 표기한다(정직 표기).
 */
@RestController
@RequestMapping("/api/documents/{id}")
class AiController(
    private val askDocument: AskDocumentService,
    private val summarizeDocument: SummarizeDocumentService,
    private val aiMode: AiMode,
) {

    @PostMapping("/ask")
    fun ask(@PathVariable id: String, @Valid @RequestBody req: AskRequest): AskResponse {
        val result = askDocument.ask(
            AskDocumentCommand(
                documentId = DocumentId(id),
                requesterId = CurrentUser.id(),
                question = req.question,
                topK = req.topK,
            ),
        )
        return AskResponse(
            answer = result.answer,
            citedOrdinals = result.citedOrdinals,
            offline = result.offline,
        )
    }

    @PostMapping("/summarize")
    fun summarize(@PathVariable id: String): SummarizeResponse {
        val summary = summarizeDocument.summarize(DocumentId(id), CurrentUser.id())
        return SummarizeResponse(summary = summary, offline = aiMode.offline)
    }
}
