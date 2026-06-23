package com.example.collab.application.port.out

import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.sharing.ShareAcl

/** 문서 ACL 영속 out port. */
interface ShareAclRepository {
    fun save(acl: ShareAcl): ShareAcl
    fun findByDocument(documentId: DocumentId): ShareAcl?
}
