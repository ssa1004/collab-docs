package com.example.collab.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * JPA 엔티티. 도메인 aggregate 와 1:1 매핑하지 않고, 어댑터 경계의 영속 모델로만 둔다
 * (도메인은 JPA 무의존). 스키마는 Flyway V1__schema.sql(H2 MODE=PostgreSQL / Postgres 호환)이 소유한다.
 */

@Entity
@Table(name = "documents")
class DocumentEntity(
    @Id
    @Column(name = "id", nullable = false)
    var id: String = "",

    @Column(name = "owner_id", nullable = false)
    var ownerId: String = "",

    @Column(name = "title", nullable = false)
    var title: String = "",

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    var content: String = "",

    @Column(name = "version", nullable = false)
    var version: Int = 0,

    @Column(name = "folder_id")
    var folderId: String? = null,
)

@Entity
@Table(name = "edit_log")
class EditLogEntity(
    // (document_id, committed_version) 가 자연키이지만 단순 surrogate id 로 둔다.
    @Id
    @Column(name = "id", nullable = false)
    var id: String = "",

    @Column(name = "document_id", nullable = false)
    var documentId: String = "",

    @Column(name = "author_id", nullable = false)
    var authorId: String = "",

    @Column(name = "op_json", nullable = false, columnDefinition = "TEXT")
    var opJson: String = "",

    @Column(name = "committed_version", nullable = false)
    var committedVersion: Int = 0,

    @Column(name = "committed_at", nullable = false)
    var committedAt: Instant = Instant.now(),
)

@Entity
@Table(name = "share_acl")
class ShareAclEntity(
    @Id
    @Column(name = "document_id", nullable = false)
    var documentId: String = "",

    @Column(name = "owner_id", nullable = false)
    var ownerId: String = "",
)

@Entity
@Table(name = "share_acl_entry")
class ShareAclEntryEntity(
    @Id
    @Column(name = "id", nullable = false)
    var id: String = "",

    @Column(name = "document_id", nullable = false)
    var documentId: String = "",

    @Column(name = "user_id", nullable = false)
    var userId: String = "",

    @Column(name = "role", nullable = false)
    var role: String = "",
)

@Entity
@Table(name = "comments")
class CommentEntity(
    @Id
    @Column(name = "id", nullable = false)
    var id: String = "",

    @Column(name = "document_id", nullable = false)
    var documentId: String = "",

    @Column(name = "author_id", nullable = false)
    var authorId: String = "",

    // anchor 종류: POINT | RANGE
    @Column(name = "anchor_kind", nullable = false)
    var anchorKind: String = "",

    @Column(name = "anchor_start", nullable = false)
    var anchorStart: Int = 0,

    // POINT 면 anchorEnd 는 의미 없음(저장만), RANGE 면 endExclusive.
    @Column(name = "anchor_end", nullable = false)
    var anchorEnd: Int = 0,

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    var body: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "resolved", nullable = false)
    var resolved: Boolean = false,
)
