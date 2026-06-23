package com.example.collab.application

import com.example.collab.application.authz.DocumentAuthorizer
import com.example.collab.application.port.out.DocumentRepository
import com.example.collab.application.port.out.DocumentSearchPort
import com.example.collab.domain.document.Document
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 제목 변경 use case (권한: edit). 제목은 본문 OT 버전과 분리되어 version 을 올리지 않는다(Document.rename).
 * 검색 색인은 제목도 포함하므로 재색인한다.
 */
@Service
class UpdateTitleService(
    private val documentRepository: DocumentRepository,
    private val searchPort: DocumentSearchPort,
    private val authorizer: DocumentAuthorizer,
) {
    @Transactional
    fun updateTitle(documentId: DocumentId, requesterId: UserId, newTitle: String): Document {
        authorizer.requireEdit(documentId, requesterId)
        val updated = documentRepository.load(documentId).rename(newTitle)
        val saved = documentRepository.save(updated)
        searchPort.index(saved)
        return saved
    }
}
