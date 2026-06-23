package com.example.collab.adapter.`in`.web.dto

import com.example.collab.application.port.out.DocumentSearchHit
import com.example.collab.application.port.out.EditLogEntry
import com.example.collab.domain.comment.Comment
import com.example.collab.domain.comment.CommentAnchor
import com.example.collab.domain.document.Document
import com.example.collab.domain.sharing.AclEntry
import com.example.collab.domain.sharing.Role
import com.example.collab.domain.sharing.ShareAcl
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.time.Instant

/** REST 요청/응답 DTO 모음. 도메인 ↔ 와이어 변환은 여기서 모은다. */

// ---- documents --------------------------------------------------------------

data class CreateDocumentRequest(
    @field:NotBlank val title: String,
    val content: String = "",
    val folderId: String? = null,
)

data class UpdateTitleRequest(
    @field:NotBlank val title: String,
)

data class DocumentResponse(
    val id: String,
    val ownerId: String,
    val title: String,
    val content: String,
    val version: Int,
    val folderId: String?,
) {
    companion object {
        fun of(d: Document) = DocumentResponse(
            id = d.id.value,
            ownerId = d.ownerId.value,
            title = d.title,
            content = d.content,
            version = d.version,
            folderId = d.folderId?.value,
        )
    }
}

// ---- edit -------------------------------------------------------------------

data class ApplyEditRequest(
    val op: OperationDto,
    @field:Min(0) val baseVersion: Int,
)

data class ApplyEditResponse(
    /** 서버가 실제 적용한(rebase 된) op — 클라이언트가 ack 로 받아 자기 상태를 맞춘다. */
    val transformedOp: OperationDto,
    val newVersion: Int,
)

// ---- versions ---------------------------------------------------------------

data class VersionEntryResponse(
    val committedVersion: Int,
    val authorId: String,
    val op: OperationDto,
    val committedAt: Instant,
) {
    companion object {
        fun of(e: EditLogEntry) = VersionEntryResponse(
            committedVersion = e.committedVersion,
            authorId = e.authorId.value,
            op = OperationDto.fromDomain(e.op),
            committedAt = e.committedAt,
        )
    }
}

// ---- search -----------------------------------------------------------------

data class SearchHitResponse(
    val documentId: String,
    val title: String,
    val score: Double,
) {
    companion object {
        fun of(h: DocumentSearchHit) = SearchHitResponse(h.documentId.value, h.title, h.score)
    }
}

// ---- share ------------------------------------------------------------------

data class ShareRequest(
    @field:NotBlank val targetUserId: String,
    val role: Role,
)

data class AclResponse(
    val documentId: String,
    val ownerId: String,
    val entries: List<AclEntryResponse>,
) {
    companion object {
        fun of(acl: ShareAcl) = AclResponse(
            documentId = acl.documentId.value,
            ownerId = acl.ownerId.value,
            entries = acl.entries.map { AclEntryResponse.of(it) },
        )
    }
}

data class AclEntryResponse(val userId: String, val role: Role) {
    companion object {
        fun of(e: AclEntry) = AclEntryResponse(e.userId.value, e.role)
    }
}

// ---- comments ---------------------------------------------------------------

data class AddCommentRequest(
    val anchor: AnchorDto,
    @field:NotBlank val body: String,
)

/** anchor 와이어: {"kind":"point","position":n} 또는 {"kind":"range","start":a,"endExclusive":b}. */
data class AnchorDto(
    val kind: String,
    val position: Int? = null,
    val start: Int? = null,
    val endExclusive: Int? = null,
) {
    fun toDomain(): CommentAnchor = when (kind.lowercase()) {
        "point" -> CommentAnchor.Point(requireNotNull(position) { "point anchor requires 'position'" })
        "range" -> CommentAnchor.Range(
            requireNotNull(start) { "range anchor requires 'start'" },
            requireNotNull(endExclusive) { "range anchor requires 'endExclusive'" },
        )
        else -> throw IllegalArgumentException("unknown anchor kind: '$kind' (expected point|range)")
    }

    companion object {
        fun of(a: CommentAnchor): AnchorDto = when (a) {
            is CommentAnchor.Point -> AnchorDto(kind = "point", position = a.position)
            is CommentAnchor.Range -> AnchorDto(kind = "range", start = a.start, endExclusive = a.endExclusive)
        }
    }
}

data class CommentResponse(
    val id: String,
    val documentId: String,
    val authorId: String,
    val anchor: AnchorDto,
    val body: String,
    val createdAt: Instant,
    val resolved: Boolean,
) {
    companion object {
        fun of(c: Comment) = CommentResponse(
            id = c.id.value,
            documentId = c.documentId.value,
            authorId = c.authorId.value,
            anchor = AnchorDto.of(c.anchor),
            body = c.body,
            createdAt = c.createdAt,
            resolved = c.resolved,
        )
    }
}

// ---- ai ---------------------------------------------------------------------

data class AskRequest(
    @field:NotBlank val question: String,
    @field:Min(1) val topK: Int = 4,
)

data class AskResponse(
    val answer: String,
    val citedOrdinals: List<Int>,
    /** offline 결정론 AI 모드 산출물 여부(정직 표기). */
    val offline: Boolean,
)

data class SummarizeResponse(
    val summary: String,
    val offline: Boolean,
)
