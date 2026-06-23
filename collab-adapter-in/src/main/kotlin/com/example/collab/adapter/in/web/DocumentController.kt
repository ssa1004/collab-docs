package com.example.collab.adapter.`in`.web

import com.example.collab.adapter.`in`.web.dto.AclResponse
import com.example.collab.adapter.`in`.web.dto.AddCommentRequest
import com.example.collab.adapter.`in`.web.dto.ApplyEditRequest
import com.example.collab.adapter.`in`.web.dto.ApplyEditResponse
import com.example.collab.adapter.`in`.web.dto.CommentResponse
import com.example.collab.adapter.`in`.web.dto.CreateDocumentRequest
import com.example.collab.adapter.`in`.web.dto.DocumentResponse
import com.example.collab.adapter.`in`.web.dto.OperationDto
import com.example.collab.adapter.`in`.web.dto.ShareRequest
import com.example.collab.adapter.`in`.web.dto.UpdateTitleRequest
import com.example.collab.adapter.`in`.web.dto.VersionEntryResponse
import com.example.collab.application.AddCommentCommand
import com.example.collab.application.AddCommentService
import com.example.collab.application.ApplyEditCommand
import com.example.collab.application.ApplyEditService
import com.example.collab.application.CreateDocumentCommand
import com.example.collab.application.CreateDocumentService
import com.example.collab.application.GetDocumentService
import com.example.collab.application.ListCommentsService
import com.example.collab.application.ListDocumentsService
import com.example.collab.application.ListVersionsService
import com.example.collab.application.ShareDocumentService
import com.example.collab.application.UpdateTitleService
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.FolderId
import com.example.collab.domain.document.UserId
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * 문서 REST 컨트롤러: 생성/조회/목록/제목변경/편집/버전/공유/코멘트.
 *
 * userId 는 CurrentUser 가 인증 주체에서 뽑는다(dev 는 고정 demo 사용자).
 * 도메인 예외(AccessDenied/NotFound/IllegalOperation)는 GlobalExceptionHandler 가 ProblemDetail 로 매핑.
 */
@RestController
@RequestMapping("/api/documents")
class DocumentController(
    private val createDocument: CreateDocumentService,
    private val getDocument: GetDocumentService,
    private val listDocuments: ListDocumentsService,
    private val updateTitle: UpdateTitleService,
    private val applyEdit: ApplyEditService,
    private val listVersions: ListVersionsService,
    private val shareDocument: ShareDocumentService,
    private val addComment: AddCommentService,
    private val listComments: ListCommentsService,
) {

    @PostMapping
    fun create(@Valid @RequestBody req: CreateDocumentRequest): ResponseEntity<DocumentResponse> {
        val doc = createDocument.create(
            CreateDocumentCommand(
                ownerId = CurrentUser.id(),
                title = req.title,
                content = req.content,
                folderId = req.folderId?.let { FolderId(it) },
            ),
        )
        return ResponseEntity
            .created(URI.create("/api/documents/${doc.id.value}"))
            .body(DocumentResponse.of(doc))
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): DocumentResponse =
        DocumentResponse.of(getDocument.get(DocumentId(id), CurrentUser.id()))

    @GetMapping
    fun list(): List<DocumentResponse> =
        listDocuments.listOwned(CurrentUser.id()).map { DocumentResponse.of(it) }

    @PutMapping("/{id}/title")
    fun updateTitle(@PathVariable id: String, @Valid @RequestBody req: UpdateTitleRequest): DocumentResponse =
        DocumentResponse.of(updateTitle.updateTitle(DocumentId(id), CurrentUser.id(), req.title))

    /**
     * 편집 적용(서버 권위 OT). (op, baseVersion) 을 받아 동시 op 들에 대해 transform 후 적용하고,
     * 변환된 op + 새 version 을 돌려준다. 같은 baseVersion 으로 두 번째 편집이 와도 서버가 rebase 해 수렴시킨다.
     */
    @PostMapping("/{id}/edit")
    fun edit(@PathVariable id: String, @Valid @RequestBody req: ApplyEditRequest): ApplyEditResponse {
        val result = applyEdit.apply(
            ApplyEditCommand(
                documentId = DocumentId(id),
                editorId = CurrentUser.id(),
                op = req.op.toDomain(),
                baseVersion = req.baseVersion,
            ),
        )
        return ApplyEditResponse(
            transformedOp = OperationDto.fromDomain(result.transformedOp),
            newVersion = result.newVersion,
        )
    }

    @GetMapping("/{id}/versions")
    fun versions(@PathVariable id: String): List<VersionEntryResponse> =
        listVersions.list(DocumentId(id), CurrentUser.id()).map { VersionEntryResponse.of(it) }

    @PutMapping("/{id}/share")
    fun share(@PathVariable id: String, @Valid @RequestBody req: ShareRequest): AclResponse {
        val acl = shareDocument.share(DocumentId(id), CurrentUser.id(), UserId(req.targetUserId), req.role)
        return AclResponse.of(acl)
    }

    @DeleteMapping("/{id}/share")
    fun revokeShare(@PathVariable id: String, @RequestParam targetUserId: String): AclResponse {
        val acl = shareDocument.revoke(DocumentId(id), CurrentUser.id(), UserId(targetUserId))
        return AclResponse.of(acl)
    }

    @PostMapping("/{id}/comments")
    fun addComment(@PathVariable id: String, @Valid @RequestBody req: AddCommentRequest): ResponseEntity<CommentResponse> {
        val comment = addComment.addComment(
            AddCommentCommand(
                documentId = DocumentId(id),
                authorId = CurrentUser.id(),
                anchor = req.anchor.toDomain(),
                body = req.body,
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(CommentResponse.of(comment))
    }

    @GetMapping("/{id}/comments")
    fun listComments(@PathVariable id: String): List<CommentResponse> =
        listComments.list(DocumentId(id), CurrentUser.id()).map { CommentResponse.of(it) }
}
