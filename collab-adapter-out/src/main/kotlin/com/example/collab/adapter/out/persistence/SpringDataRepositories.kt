package com.example.collab.adapter.out.persistence

import org.springframework.data.jpa.repository.JpaRepository

/** Spring Data JPA 리포지토리(인프라). 도메인 port 어댑터가 이걸 감싼다. */

interface DocumentJpaRepository : JpaRepository<DocumentEntity, String> {
    fun findByOwnerId(ownerId: String): List<DocumentEntity>
}

interface EditLogJpaRepository : JpaRepository<EditLogEntity, String> {
    fun findByDocumentIdAndCommittedVersionGreaterThanOrderByCommittedVersionAsc(
        documentId: String,
        committedVersion: Int,
    ): List<EditLogEntity>

    fun findByDocumentIdOrderByCommittedVersionAsc(documentId: String): List<EditLogEntity>
}

interface ShareAclJpaRepository : JpaRepository<ShareAclEntity, String>

interface ShareAclEntryJpaRepository : JpaRepository<ShareAclEntryEntity, String> {
    fun findByDocumentId(documentId: String): List<ShareAclEntryEntity>
    fun deleteByDocumentId(documentId: String)
}

interface CommentJpaRepository : JpaRepository<CommentEntity, String> {
    fun findByDocumentIdOrderByCreatedAtAsc(documentId: String): List<CommentEntity>
}
