package com.example.collab.domain.comment

import com.example.collab.domain.document.CommentId
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import java.time.Instant

/**
 * 본문 내 위치(anchor)에 달린 코멘트.
 *
 * anchor 는 단일 지점(Point) 또는 범위(Range)다. 위치는 0-based 문자 인덱스.
 * 본문이 OT 로 바뀌면 anchor 재정렬이 필요하지만(향후 과제), 여기선 생성 시점 좌표를 고정 저장한다.
 */
sealed interface CommentAnchor {
    /** 단일 caret 위치. */
    data class Point(val position: Int) : CommentAnchor {
        init { require(position >= 0) { "position must be >= 0" } }
    }
    /** [start, endExclusive) 범위. */
    data class Range(val start: Int, val endExclusive: Int) : CommentAnchor {
        init {
            require(start >= 0) { "start must be >= 0" }
            require(endExclusive > start) { "endExclusive must be > start" }
        }
        val length: Int get() = endExclusive - start
    }
}

data class Comment(
    val id: CommentId,
    val documentId: DocumentId,
    val authorId: UserId,
    val anchor: CommentAnchor,
    val body: String,
    val createdAt: Instant,
    val resolved: Boolean = false,
) {
    init { require(body.isNotBlank()) { "comment body must not be blank" } }

    fun resolve(): Comment = copy(resolved = true)
    fun reopen(): Comment = copy(resolved = false)

    companion object {
        fun create(
            id: CommentId,
            documentId: DocumentId,
            authorId: UserId,
            anchor: CommentAnchor,
            body: String,
            createdAt: Instant = Instant.now(),
        ) = Comment(id, documentId, authorId, anchor, body, createdAt)
    }
}
