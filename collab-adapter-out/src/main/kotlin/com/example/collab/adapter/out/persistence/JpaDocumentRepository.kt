package com.example.collab.adapter.out.persistence

import com.example.collab.application.port.out.DocumentRepository
import com.example.collab.domain.document.Document
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.FolderId
import com.example.collab.domain.document.UserId
import org.springframework.stereotype.Repository

/**
 * DocumentRepository out-port 의 JPA 어댑터.
 * H2(default) / Postgres(prod) 어느 쪽이든 datasource 만 바꾸면 동일하게 동작한다.
 */
@Repository
class JpaDocumentRepository(
    private val jpa: DocumentJpaRepository,
) : DocumentRepository {

    override fun save(document: Document): Document {
        jpa.save(document.toEntity())
        return document
    }

    override fun findById(id: DocumentId): Document? =
        jpa.findById(id.value).map { it.toDomain() }.orElse(null)

    override fun listByOwner(ownerId: UserId): List<Document> =
        jpa.findByOwnerId(ownerId.value).map { it.toDomain() }
}

private fun Document.toEntity() = DocumentEntity(
    id = id.value,
    ownerId = ownerId.value,
    title = title,
    content = content,
    version = version,
    folderId = folderId?.value,
)

private fun DocumentEntity.toDomain() = Document(
    id = DocumentId(id),
    ownerId = UserId(ownerId),
    title = title,
    content = content,
    version = version,
    folderId = folderId?.let { FolderId(it) },
)
