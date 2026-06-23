package com.example.collab.application

import com.example.collab.application.port.out.DocumentRepository
import com.example.collab.domain.document.Document
import com.example.collab.domain.document.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 소유 문서 목록 조회 use case. (공유받은 문서 목록은 향후 과제 — 여기선 owner 범위.) */
@Service
class ListDocumentsService(
    private val documentRepository: DocumentRepository,
) {
    @Transactional(readOnly = true)
    fun listOwned(ownerId: UserId): List<Document> = documentRepository.listByOwner(ownerId)
}
