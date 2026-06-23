package com.example.collab.application

import com.example.collab.application.authz.DocumentAuthorizer
import com.example.collab.application.port.out.CommentRepository
import com.example.collab.domain.comment.Comment
import com.example.collab.domain.comment.CommentAnchor
import com.example.collab.domain.document.CommentId
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * 코멘트 추가 use case. 코멘트 작성은 edit 권한을 요구한다(viewer 는 읽기만).
 */
@Service
class AddCommentService(
    private val commentRepository: CommentRepository,
    private val authorizer: DocumentAuthorizer,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun addComment(command: AddCommentCommand): Comment {
        authorizer.requireEdit(command.documentId, command.authorId)
        val comment = Comment.create(
            id = command.commentId ?: CommentId.random(),
            documentId = command.documentId,
            authorId = command.authorId,
            anchor = command.anchor,
            body = command.body,
            createdAt = Instant.now(clock),
        )
        return commentRepository.save(comment)
    }
}

data class AddCommentCommand(
    val documentId: DocumentId,
    val authorId: UserId,
    val anchor: CommentAnchor,
    val body: String,
    val commentId: CommentId? = null,
)
