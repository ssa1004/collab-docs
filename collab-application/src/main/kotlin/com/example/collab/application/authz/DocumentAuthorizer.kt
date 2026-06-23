package com.example.collab.application.authz

import com.example.collab.application.port.out.DocumentNotFoundException
import com.example.collab.application.port.out.ShareAclRepository
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import com.example.collab.domain.sharing.AccessDeniedException
import com.example.collab.domain.sharing.Role
import com.example.collab.domain.sharing.ShareAcl
import org.springframework.stereotype.Component

/**
 * 권한 검사 헬퍼. 문서 ACL 을 로드해 도메인 정책(ShareAcl/Role)으로 판정한다.
 * ACL 이 없으면(아직 공유 설정 전) "문서 미존재"로 본다 — 생성 시 ownerForOwner ACL 을 함께 만드는 게 계약.
 *
 * 권한 위반은 [AccessDeniedException](도메인), 문서 부재는 [DocumentNotFoundException] 을 던진다.
 */
@Component
class DocumentAuthorizer(
    private val aclRepository: ShareAclRepository,
) {
    fun loadAcl(documentId: DocumentId): ShareAcl =
        aclRepository.findByDocument(documentId) ?: throw DocumentNotFoundException(documentId)

    fun requireView(documentId: DocumentId, userId: UserId): ShareAcl =
        loadAcl(documentId).also { it.requireView(userId) }

    fun requireEdit(documentId: DocumentId, userId: UserId): ShareAcl =
        loadAcl(documentId).also { it.requireEdit(userId) }

    fun requireManageSharing(documentId: DocumentId, userId: UserId): ShareAcl =
        loadAcl(documentId).also { it.requireManageSharing(userId) }

    fun roleOf(documentId: DocumentId, userId: UserId): Role? = loadAcl(documentId).roleOf(userId)
}
