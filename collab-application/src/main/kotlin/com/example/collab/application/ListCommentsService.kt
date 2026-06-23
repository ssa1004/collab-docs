package com.example.collab.application

import com.example.collab.application.authz.DocumentAuthorizer
import com.example.collab.application.port.out.CommentRepository
import com.example.collab.domain.comment.Comment
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 코멘트 목록 조회 use case (권한: view). */
@Service
class ListCommentsService(
    private val commentRepository: CommentRepository,
    private val authorizer: DocumentAuthorizer,
) {
    @Transactional(readOnly = true)
    fun list(documentId: DocumentId, requesterId: UserId): List<Comment> {
        authorizer.requireView(documentId, requesterId)
        return commentRepository.listByDocument(documentId)
    }
}
