package com.example.collab.application.port.out

import com.example.collab.domain.comment.Comment
import com.example.collab.domain.document.DocumentId

/** 코멘트 영속 out port. */
interface CommentRepository {
    fun save(comment: Comment): Comment
    fun listByDocument(documentId: DocumentId): List<Comment>
}
