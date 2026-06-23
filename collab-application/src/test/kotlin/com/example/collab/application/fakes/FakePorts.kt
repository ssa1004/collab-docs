package com.example.collab.application.fakes

import com.example.collab.application.port.out.AiAnswer
import com.example.collab.application.port.out.AiAssistPort
import com.example.collab.application.port.out.BlobStorePort
import com.example.collab.application.port.out.ChunkHit
import com.example.collab.application.port.out.CommentRepository
import com.example.collab.application.port.out.DocumentRepository
import com.example.collab.application.port.out.DocumentSearchHit
import com.example.collab.application.port.out.DocumentSearchPort
import com.example.collab.application.port.out.EditLogEntry
import com.example.collab.application.port.out.EditLogRepository
import com.example.collab.application.port.out.PresenceEvent
import com.example.collab.application.port.out.PresenceListener
import com.example.collab.application.port.out.PresencePort
import com.example.collab.application.port.out.PresenceUpdate
import com.example.collab.application.port.out.ShareAclRepository
import com.example.collab.application.port.out.Subscription
import com.example.collab.domain.comment.Comment
import com.example.collab.domain.document.Document
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import com.example.collab.domain.edit.TextOperation
import com.example.collab.domain.rag.DocumentChunk
import com.example.collab.domain.sharing.ShareAcl

/** 인메모리 fake out 포트 모음. 어댑터 없이 use case 오케스트레이션만 검증한다. */

class FakeDocumentRepository : DocumentRepository {
    val store = LinkedHashMap<DocumentId, Document>()
    override fun save(document: Document): Document { store[document.id] = document; return document }
    override fun findById(id: DocumentId): Document? = store[id]
    override fun listByOwner(ownerId: UserId): List<Document> = store.values.filter { it.ownerId == ownerId }
}

class FakeEditLogRepository : EditLogRepository {
    val entries = ArrayList<EditLogEntry>()
    override fun append(entry: EditLogEntry): EditLogEntry { entries.add(entry); return entry }
    override fun opsCommittedAfter(documentId: DocumentId, baseVersion: Int): List<EditLogEntry> =
        entries.filter { it.documentId == documentId && it.committedVersion > baseVersion }
            .sortedBy { it.committedVersion }
    override fun history(documentId: DocumentId): List<EditLogEntry> =
        entries.filter { it.documentId == documentId }.sortedBy { it.committedVersion }
}

class FakeShareAclRepository : ShareAclRepository {
    val store = HashMap<DocumentId, ShareAcl>()
    override fun save(acl: ShareAcl): ShareAcl { store[acl.documentId] = acl; return acl }
    override fun findByDocument(documentId: DocumentId): ShareAcl? = store[documentId]
}

class FakeCommentRepository : CommentRepository {
    val store = ArrayList<Comment>()
    override fun save(comment: Comment): Comment { store.add(comment); return comment }
    override fun listByDocument(documentId: DocumentId): List<Comment> = store.filter { it.documentId == documentId }
}

/** 발행된 edit/presence 이벤트를 기록만 한다. */
class FakePresencePort : PresencePort {
    data class PublishedEdit(val documentId: DocumentId, val op: TextOperation, val version: Int)
    val edits = ArrayList<PublishedEdit>()
    val presences = ArrayList<PresenceUpdate>()
    private val listeners = HashMap<DocumentId, MutableList<PresenceListener>>()

    override fun publishEdit(documentId: DocumentId, transformedOp: TextOperation, newVersion: Int) {
        edits.add(PublishedEdit(documentId, transformedOp, newVersion))
        listeners[documentId]?.forEach { it.onEvent(PresenceEvent.Edit(documentId, transformedOp, newVersion)) }
    }
    override fun publishPresence(documentId: DocumentId, presence: PresenceUpdate) {
        presences.add(presence)
        listeners[documentId]?.forEach { it.onEvent(PresenceEvent.Presence(documentId, presence)) }
    }
    override fun subscribe(documentId: DocumentId, listener: PresenceListener): Subscription {
        val list = listeners.getOrPut(documentId) { ArrayList() }
        list.add(listener)
        return Subscription { list.remove(listener) }
    }
}

/** 키워드 겹침 점수 기반 인메모리 검색. */
class FakeDocumentSearchPort : DocumentSearchPort {
    val indexed = LinkedHashMap<DocumentId, Document>()
    override fun index(document: Document) { indexed[document.id] = document }

    override fun search(query: String, ownerId: UserId, limit: Int): List<DocumentSearchHit> {
        val terms = tokens(query)
        return indexed.values.filter { it.ownerId == ownerId }
            .map { it to score(terms, tokens(it.title + " " + it.content)) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { DocumentSearchHit(it.first.id, it.first.title, it.second) }
    }

    override fun searchChunks(documentId: DocumentId, query: String, chunks: List<DocumentChunk>, limit: Int): List<ChunkHit> {
        val terms = tokens(query)
        return chunks.map { ChunkHit(it, score(terms, tokens(it.text))) }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun tokens(s: String) = s.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }.toSet()
    private fun score(q: Set<String>, doc: Set<String>): Double = q.count { it in doc }.toDouble()
}

class FakeBlobStorePort : BlobStorePort {
    val store = HashMap<String, ByteArray>()
    override fun put(key: String, bytes: ByteArray) { store[key] = bytes }
    override fun get(key: String): ByteArray? = store[key]
    override fun delete(key: String) { store.remove(key) }
}

/**
 * 결정론 offline AI fake.
 * - ask: 받은 컨텍스트 청크의 ordinal 을 그대로 인용하고, 청크 텍스트 일부를 답변에 박아 retrieval 배선을 검증 가능하게 한다.
 * - embed: 토큰 해시 기반 결정론 벡터.
 * - summarize: 첫 문장 추출.
 */
class FakeAiAssistPort(private val cannedAnswer: String = "CANNED") : AiAssistPort {
    var lastAskQuestion: String? = null
    var lastAskContext: List<DocumentChunk> = emptyList()

    override fun embed(text: String): FloatArray {
        val v = FloatArray(8)
        for (tok in text.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }) {
            v[Math.floorMod(tok.hashCode(), 8)] += 1f
        }
        return v
    }

    override fun ask(question: String, contextChunks: List<DocumentChunk>): AiAnswer {
        lastAskQuestion = question
        lastAskContext = contextChunks
        val cited = contextChunks.map { it.ordinal }
        val snippet = contextChunks.firstOrNull()?.text?.take(20) ?: ""
        return AiAnswer(answer = "$cannedAnswer [ctx=$snippet]", citedOrdinals = cited, offline = true)
    }

    override fun summarize(text: String): String = text.trim().split(Regex("(?<=[.!?])\\s+")).first()
}
