package com.example.collab.application

import com.example.collab.application.authz.DocumentAuthorizer
import com.example.collab.application.fakes.FakeDocumentRepository
import com.example.collab.application.fakes.FakeDocumentSearchPort
import com.example.collab.application.fakes.FakeEditLogRepository
import com.example.collab.application.fakes.FakePresencePort
import com.example.collab.application.fakes.FakeShareAclRepository
import com.example.collab.domain.document.Document
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import com.example.collab.domain.edit.OperationalTransform
import com.example.collab.domain.edit.TextOperation
import com.example.collab.domain.sharing.AccessDeniedException
import com.example.collab.domain.sharing.Role
import com.example.collab.domain.sharing.ShareAcl
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApplyEditServiceTest {

    private lateinit var docs: FakeDocumentRepository
    private lateinit var log: FakeEditLogRepository
    private lateinit var presence: FakePresencePort
    private lateinit var search: FakeDocumentSearchPort
    private lateinit var acls: FakeShareAclRepository
    private lateinit var service: ApplyEditService

    private val docId = DocumentId("doc-1")
    private val alice = UserId("alice")
    private val bob = UserId("bob")
    private val viewer = UserId("viewer")

    @BeforeEach
    fun setup() {
        docs = FakeDocumentRepository()
        log = FakeEditLogRepository()
        presence = FakePresencePort()
        search = FakeDocumentSearchPort()
        acls = FakeShareAclRepository()
        service = ApplyEditService(docs, log, presence, search, DocumentAuthorizer(acls))

        // 기준 문서: "Hello" (version 0), alice=owner, bob=editor, viewer=viewer
        docs.save(Document(docId, alice, "title", "Hello", version = 0))
        acls.save(
            ShareAcl.forOwner(docId, alice)
                .grant(bob, Role.EDITOR)
                .grant(viewer, Role.VIEWER),
        )
    }

    @Test
    fun `single edit applies and increments version, broadcasts and logs`() {
        val result = service.apply(
            ApplyEditCommand(docId, alice, TextOperation.Insert(5, " World"), baseVersion = 0),
        )

        assertEquals(1, result.newVersion)
        assertEquals("Hello World", docs.findById(docId)!!.content)
        // 동시성 없음 → op 그대로
        assertEquals(TextOperation.Insert(5, " World"), result.transformedOp)
        // 로그/브로드캐스트/색인 배선
        assertEquals(1, log.history(docId).size)
        assertEquals(1, presence.edits.size)
        assertEquals(1, presence.edits[0].version)
        assertTrue(search.indexed.containsKey(docId))
    }

    @Test
    fun `two concurrent edits on same base version both apply, converge, and the second is transformed`() {
        // 둘 다 base="Hello"(version 0)에서 동시에 편집을 만든다.
        // alice: 위치 0 에 "A" 삽입 → "AHello"
        // bob:   위치 5(끝) 에 "B" 삽입 → "HelloB"
        // 둘이 같은 baseVersion=0 으로 들어온다. 서버가 순차 커밋하며 두 번째를 transform 해야 한다.

        val aliceOp = TextOperation.Insert(0, "A")
        val bobOp = TextOperation.Insert(5, "B")

        // 1) alice 먼저 커밋 (version 0 -> 1)
        val r1 = service.apply(ApplyEditCommand(docId, alice, aliceOp, baseVersion = 0))
        assertEquals(1, r1.newVersion)
        assertEquals("AHello", docs.findById(docId)!!.content)

        // 2) bob 은 여전히 baseVersion=0 으로 들어온다(alice 편집을 못 봄).
        //    서버는 alice 의 op(Insert(0,"A"))에 대해 bob 의 op 를 transform 해야 한다.
        //    bob.pos=5 > alice.pos=0 이므로 +1 shift → Insert(6,"B").
        val r2 = service.apply(ApplyEditCommand(docId, bob, bobOp, baseVersion = 0))
        assertEquals(2, r2.newVersion)
        assertEquals(TextOperation.Insert(6, "B"), r2.transformedOp) // 두 번째 op 가 transform 됨
        assertEquals("AHelloB", docs.findById(docId)!!.content)

        // 3) 수렴 확인: 같은 base 에 두 op 를 반대 순서로 OT 적용해도 같은 결과여야 한다.
        val base = "Hello"
        val bobFirst = OperationalTransform.apply(
            OperationalTransform.apply(base, bobOp),
            OperationalTransform.transform(aliceOp, bobOp, aHasPriority = false),
        )
        assertEquals("AHelloB", bobFirst) // 서버 권위 결과와 동일

        // 브로드캐스트된 두 번째 op 도 변환된 것이어야 한다(게이트웨이 fan-out 정합성)
        assertEquals(TextOperation.Insert(6, "B"), presence.edits.last().op)
    }

    @Test
    fun `concurrent overlapping insert at same position is deterministically ordered`() {
        // alice, bob 둘 다 위치 0 에 삽입. 먼저 커밋된 alice 가 우선, bob 은 뒤로 밀린다.
        val r1 = service.apply(ApplyEditCommand(docId, alice, TextOperation.Insert(0, "A"), baseVersion = 0))
        assertEquals("AHello", docs.findById(docId)!!.content) // alice 먼저 커밋된 상태
        assertEquals(1, r1.newVersion)

        val r2 = service.apply(ApplyEditCommand(docId, bob, TextOperation.Insert(0, "B"), baseVersion = 0))
        assertEquals(TextOperation.Insert(1, "B"), r2.transformedOp) // bob 우선권 없음 → 뒤로
        assertEquals("ABHello", docs.findById(docId)!!.content)
        assertEquals(2, r2.newVersion)
    }

    @Test
    fun `viewer cannot edit - AccessDeniedException`() {
        assertFailsWith<AccessDeniedException> {
            service.apply(ApplyEditCommand(docId, viewer, TextOperation.Insert(0, "X"), baseVersion = 0))
        }
        // 거부되면 문서/로그 불변
        assertEquals("Hello", docs.findById(docId)!!.content)
        assertEquals(0, log.history(docId).size)
    }

    @Test
    fun `baseVersion ahead of document is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            service.apply(ApplyEditCommand(docId, alice, TextOperation.Insert(0, "X"), baseVersion = 99))
        }
    }
}
