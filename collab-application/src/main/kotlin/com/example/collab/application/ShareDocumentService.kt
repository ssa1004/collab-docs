package com.example.collab.application

import com.example.collab.application.authz.DocumentAuthorizer
import com.example.collab.application.port.out.ShareAclRepository
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import com.example.collab.domain.sharing.Role
import com.example.collab.domain.sharing.ShareAcl
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 문서 공유 use case. 공유/권한 변경은 OWNER 만 가능(권한 위반 시 AccessDeniedException).
 */
@Service
class ShareDocumentService(
    private val aclRepository: ShareAclRepository,
    private val authorizer: DocumentAuthorizer,
) {
    /** target 사용자에게 role 부여(또는 변경). */
    @Transactional
    fun share(documentId: DocumentId, requesterId: UserId, targetUserId: UserId, role: Role): ShareAcl {
        val acl = authorizer.requireManageSharing(documentId, requesterId)
        val updated = acl.grant(targetUserId, role)
        return aclRepository.save(updated)
    }

    /** target 사용자 권한 회수. */
    @Transactional
    fun revoke(documentId: DocumentId, requesterId: UserId, targetUserId: UserId): ShareAcl {
        val acl = authorizer.requireManageSharing(documentId, requesterId)
        val updated = acl.revoke(targetUserId)
        return aclRepository.save(updated)
    }
}
