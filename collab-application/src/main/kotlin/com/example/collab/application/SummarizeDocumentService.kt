package com.example.collab.application

import com.example.collab.application.authz.DocumentAuthorizer
import com.example.collab.application.port.out.AiAssistPort
import com.example.collab.application.port.out.DocumentRepository
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 문서 요약 use case (권한: view). */
@Service
class SummarizeDocumentService(
    private val documentRepository: DocumentRepository,
    private val aiPort: AiAssistPort,
    private val authorizer: DocumentAuthorizer,
) {
    @Transactional(readOnly = true)
    fun summarize(documentId: DocumentId, requesterId: UserId): String {
        authorizer.requireView(documentId, requesterId)
        val document = documentRepository.load(documentId)
        if (document.content.isBlank()) return ""
        return aiPort.summarize(document.content)
    }
}
