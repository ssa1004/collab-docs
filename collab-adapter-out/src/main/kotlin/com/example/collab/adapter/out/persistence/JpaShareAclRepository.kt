package com.example.collab.adapter.out.persistence

import com.example.collab.application.port.out.ShareAclRepository
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import com.example.collab.domain.sharing.AclEntry
import com.example.collab.domain.sharing.Role
import com.example.collab.domain.sharing.ShareAcl
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * ShareAclRepository out-port 의 JPA 어댑터.
 * ACL 헤더(share_acl) + 엔트리(share_acl_entry) 를 함께 저장/조회한다.
 * save 는 "엔트리 전량 교체"(도메인 ShareAcl 이 불변 전체 상태를 들고 오므로) 시맨틱.
 */
@Repository
class JpaShareAclRepository(
    private val aclJpa: ShareAclJpaRepository,
    private val entryJpa: ShareAclEntryJpaRepository,
) : ShareAclRepository {

    @Transactional
    override fun save(acl: ShareAcl): ShareAcl {
        aclJpa.save(ShareAclEntity(documentId = acl.documentId.value, ownerId = acl.ownerId.value))
        // 전체 교체: 기존 엔트리 제거 후 현재 상태로 재기록.
        entryJpa.deleteByDocumentId(acl.documentId.value)
        acl.entries.forEach { e ->
            entryJpa.save(
                ShareAclEntryEntity(
                    id = UUID.randomUUID().toString(),
                    documentId = acl.documentId.value,
                    userId = e.userId.value,
                    role = e.role.name,
                ),
            )
        }
        return acl
    }

    @Transactional(readOnly = true)
    override fun findByDocument(documentId: DocumentId): ShareAcl? {
        val header = aclJpa.findById(documentId.value).orElse(null) ?: return null
        val entries = entryJpa.findByDocumentId(documentId.value)
            .map { AclEntry(UserId(it.userId), Role.valueOf(it.role)) }
        return ShareAcl(DocumentId(header.documentId), UserId(header.ownerId), entries)
    }
}
