package com.example.collab.application

import com.example.collab.application.authz.DocumentAuthorizer
import com.example.collab.application.fakes.FakeAiAssistPort
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
import kotlin.test.assertTrue

/** RAG 파이프라인 배선: 청크 분할 → retrieve → ask → 인용 검증. */
class AskDocumentServiceTest {

    private lateinit var docs: FakeDocumentRepository
    private lateinit var search: FakeDocumentSearchPort
    private lateinit var ai: FakeAiAssistPort
    private lateinit var acls: FakeShareAclRepository
    private lateinit var service: AskDocumentService

    private val docId = DocumentId("doc-1")
    private val owner = UserId("owner")
    private val stranger = UserId("stranger")

    // 3개의 뚜렷한 문단 → 3 청크. 질문은 두 번째 문단(ordinal 1)에만 매칭되도록 설계.
    private val content = """
        Penguins are flightless birds living in the southern hemisphere.

        The mitochondria is the powerhouse of the cell and produces ATP energy.

        Coffee is a brewed drink prepared from roasted coffee beans.
    """.trimIndent()

    @BeforeEach
    fun setup() {
        docs = FakeDocumentRepository()
        search = FakeDocumentSearchPort()
        ai = FakeAiAssistPort(cannedAnswer = "ANSWER")
        acls = FakeShareAclRepository()
        service = AskDocumentService(docs, search, ai, DocumentAuthorizer(acls))

        docs.save(Document(docId, owner, "bio", content, version = 0))
        acls.save(ShareAcl.forOwner(docId, owner))
    }

    @Test
    fun `RAG retrieves relevant chunk and cites its ordinal`() {
        // 질문 어휘("mitochondria","ATP")가 오직 두 번째 문단(ordinal 1)에만 등장하도록 설계
        // (다른 문단과 stopword 조차 안 겹치게 해서 키워드 검색이 1번만 고르게 한다).
        val result = service.ask(
            AskDocumentCommand(docId, owner, question = "mitochondria ATP"),
        )

        // 답변은 fake 의 canned 응답 + 첫 컨텍스트 청크 일부를 담는다(retrieval 배선 증명)
        assertTrue(result.answer.startsWith("ANSWER"), "answer=${result.answer}")
        // 두 번째 문단(ordinal 1)이 인용되어야 한다(키워드 mitochondria/ATP 매칭)
        assertEquals(listOf(1), result.citedOrdinals)
        // ask 에 넘긴 컨텍스트가 실제로 mitochondria 청크였는지 확인
        assertTrue(ai.lastAskContext.any { it.text.contains("mitochondria") })
        assertTrue(ai.lastAskContext.none { it.text.contains("Penguins") })
        // offline 결정론 모드 표기
        assertTrue(result.offline)
    }

    @Test
    fun `keyword miss falls back to embedding retrieval and still answers`() {
        // 본문 어휘와 전혀 안 겹치는 질문 → 키워드 검색 0건 → 임베딩 fallback 으로라도 컨텍스트를 채운다.
        val result = service.ask(
            AskDocumentCommand(docId, owner, question = "zzz qqq xyzzy", topK = 2),
        )
        assertTrue(result.answer.startsWith("ANSWER"))
        // fallback 으로 최소 1개 컨텍스트 청크가 ask 에 전달되어야 한다
        assertTrue(ai.lastAskContext.isNotEmpty(), "embedding fallback should supply context")
        assertTrue(result.citedOrdinals.isNotEmpty())
    }

    @Test
    fun `empty document asks with no context`() {
        val emptyId = DocumentId("empty")
        docs.save(Document(emptyId, owner, "empty", "", version = 0))
        acls.save(ShareAcl.forOwner(emptyId, owner))

        val result = service.ask(AskDocumentCommand(emptyId, owner, "anything"))
        assertTrue(result.citedOrdinals.isEmpty())
        assertTrue(ai.lastAskContext.isEmpty())
    }

    @Test
    fun `stranger cannot ask`() {
        assertFailsWith<AccessDeniedException> {
            service.ask(AskDocumentCommand(docId, stranger, "What is coffee?"))
        }
    }

    @Test
    fun `summarize uses first sentence (deterministic fake)`() {
        val summarizer = SummarizeDocumentService(docs, ai, DocumentAuthorizer(acls))
        val summary = summarizer.summarize(docId, owner)
        assertTrue(summary.startsWith("Penguins are flightless birds"), "summary=$summary")
    }
}
