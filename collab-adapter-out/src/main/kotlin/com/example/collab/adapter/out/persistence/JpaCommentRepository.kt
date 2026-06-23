package com.example.collab.adapter.out.persistence

import com.example.collab.application.port.out.CommentRepository
import com.example.collab.domain.comment.Comment
import com.example.collab.domain.comment.CommentAnchor
import com.example.collab.domain.document.CommentId
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import org.springframework.stereotype.Repository

/**
 * CommentRepository out-port 의 JPA 어댑터.
 * anchor(Point/Range)를 (kind,start,end) 로 평면화해 저장한다.
 */
@Repository
class JpaCommentRepository(
    private val jpa: CommentJpaRepository,
) : CommentRepository {

    override fun save(comment: Comment): Comment {
        jpa.save(comment.toEntity())
        return comment
    }

    override fun listByDocument(documentId: DocumentId): List<Comment> =
        jpa.findByDocumentIdOrderByCreatedAtAsc(documentId.value).map { it.toDomain() }

    private fun Comment.toEntity(): CommentEntity {
        val (kind, start, end) = when (val a = anchor) {
            is CommentAnchor.Point -> Triple("POINT", a.position, a.position)
            is CommentAnchor.Range -> Triple("RANGE", a.start, a.endExclusive)
        }
        return CommentEntity(
            id = id.value,
            documentId = documentId.value,
            authorId = authorId.value,
            anchorKind = kind,
            anchorStart = start,
            anchorEnd = end,
            body = body,
            createdAt = createdAt,
            resolved = resolved,
        )
    }

    private fun CommentEntity.toDomain(): Comment {
        val anchor = when (anchorKind) {
            "POINT" -> CommentAnchor.Point(anchorStart)
            "RANGE" -> CommentAnchor.Range(anchorStart, anchorEnd)
            else -> throw IllegalStateException("unknown anchor kind: $anchorKind")
        }
        return Comment(
            id = CommentId(id),
            documentId = DocumentId(documentId),
            authorId = UserId(authorId),
            anchor = anchor,
            body = body,
            createdAt = createdAt,
            resolved = resolved,
        )
    }
}
