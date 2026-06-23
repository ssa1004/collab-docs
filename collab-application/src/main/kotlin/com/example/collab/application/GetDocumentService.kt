package com.example.collab.application

import com.example.collab.application.authz.DocumentAuthorizer
import com.example.collab.application.port.out.DocumentRepository
import com.example.collab.domain.document.Document
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 문서 조회 use case (권한: view). */
@Service
class GetDocumentService(
    private val documentRepository: DocumentRepository,
    private val authorizer: DocumentAuthorizer,
) {
    @Transactional(readOnly = true)
    fun get(documentId: DocumentId, requesterId: UserId): Document {
        authorizer.requireView(documentId, requesterId)
        return documentRepository.load(documentId)
    }
}
