package com.example.collab.application

import com.example.collab.application.authz.DocumentAuthorizer
import com.example.collab.application.port.out.EditLogEntry
import com.example.collab.application.port.out.EditLogRepository
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 문서 편집 이력(버전) 조회 use case (권한: view). */
@Service
class ListVersionsService(
    private val editLog: EditLogRepository,
    private val authorizer: DocumentAuthorizer,
) {
    @Transactional(readOnly = true)
    fun list(documentId: DocumentId, requesterId: UserId): List<EditLogEntry> {
        authorizer.requireView(documentId, requesterId)
        return editLog.history(documentId)
    }
}
