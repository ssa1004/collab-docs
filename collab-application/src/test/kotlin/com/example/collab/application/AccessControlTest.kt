package com.example.collab.application

import com.example.collab.application.authz.DocumentAuthorizer
import com.example.collab.application.fakes.FakeDocumentRepository
import com.example.collab.application.fakes.FakeDocumentSearchPort
import com.example.collab.application.fakes.FakeShareAclRepository
import com.example.collab.domain.document.Document
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import com.example.collab.domain.sharing.AccessDeniedException
import com.example.collab.domain.sharing.Role
import com.example.collab.domain.sharing.ShareAcl
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 권한(ACL) 강제: view/edit/manage-sharing 경계를 use case 레벨에서 검증. */
class AccessControlTest {

    private lateinit var docs: FakeDocumentRepository
    private lateinit var acls: FakeShareAclRepository
    private lateinit var search: FakeDocumentSearchPort
    private lateinit var authorizer: DocumentAuthorizer
    private lateinit var getService: GetDocumentService
    private lateinit var shareService: ShareDocumentService

    private val docId = DocumentId("doc-1")
    private val owner = UserId("owner")
    private val editor = UserId("editor")
    private val viewer = UserId("viewer")
    private val stranger = UserId("stranger")

    @BeforeEach
    fun setup() {
        docs = FakeDocumentRepository()
        acls = FakeShareAclRepository()
        search = FakeDocumentSearchPort()
        authorizer = DocumentAuthorizer(acls)
        getService = GetDocumentService(docs, authorizer)
        shareService = ShareDocumentService(acls, authorizer)

        docs.save(Document(docId, owner, "t", "content", version = 0))
        acls.save(
            ShareAcl.forOwner(docId, owner)
                .grant(editor, Role.EDITOR)
                .grant(viewer, Role.VIEWER),
        )
    }

    @Test
    fun `viewer can get document`() {
        assertEquals("content", getService.get(docId, viewer).content)
    }

    @Test
    fun `stranger cannot get document`() {
        assertFailsWith<AccessDeniedException> { getService.get(docId, stranger) }
    }

    @Test
    fun `only owner can share`() {
        // owner 가능
        val updated = shareService.share(docId, owner, stranger, Role.VIEWER)
        assertEquals(Role.VIEWER, updated.roleOf(stranger))

        // editor 불가(manage-sharing 은 owner 전용)
        assertFailsWith<AccessDeniedException> { shareService.share(docId, editor, stranger, Role.EDITOR) }
        // viewer 불가
        assertFailsWith<AccessDeniedException> { shareService.share(docId, viewer, stranger, Role.EDITOR) }
    }

    @Test
    fun `owner can revoke, persisted`() {
        shareService.revoke(docId, owner, editor)
        assertEquals(null, acls.findByDocument(docId)!!.roleOf(editor))
    }

    @Test
    fun `sharing grants new role that takes effect for subsequent access`() {
        // stranger 처음엔 접근 불가
        assertFailsWith<AccessDeniedException> { getService.get(docId, stranger) }
        // owner 가 viewer 권한 부여
        shareService.share(docId, owner, stranger, Role.VIEWER)
        // 이제 조회 가능
        assertEquals("content", getService.get(docId, stranger).content)
    }
}
