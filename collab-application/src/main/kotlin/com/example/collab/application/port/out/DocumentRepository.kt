package com.example.collab.application.port.out

import com.example.collab.domain.document.Document
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId

/** 문서 영속 out port. 어댑터(JPA/H2)가 구현한다. */
interface DocumentRepository {
    fun save(document: Document): Document
    fun findById(id: DocumentId): Document?
    fun listByOwner(ownerId: UserId): List<Document>

    /** 없으면 예외. 서비스가 "있어야 하는" 흐름에서 사용. */
    fun load(id: DocumentId): Document =
        findById(id) ?: throw DocumentNotFoundException(id)
}

class DocumentNotFoundException(val id: DocumentId) :
    RuntimeException("document not found: ${id.value}")
