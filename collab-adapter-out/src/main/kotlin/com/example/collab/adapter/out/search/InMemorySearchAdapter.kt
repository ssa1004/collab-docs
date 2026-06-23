package com.example.collab.adapter.out.search

import com.example.collab.application.port.out.ChunkHit
import com.example.collab.application.port.out.DocumentSearchHit
import com.example.collab.application.port.out.DocumentSearchPort
import com.example.collab.domain.document.Document
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import com.example.collab.domain.rag.DocumentChunk
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 기본(zero-infra) 검색 어댑터: 인프로세스 역색인 + 키워드 스코어링.
 *
 * - 문서 단위 색인: title + content 토큰을 모아두고 ownerId 범위에서 키워드 겹침 + tf 가중 점수로 랭킹.
 * - RAG 청크 검색: 주어진 청크들에 대해 질의 토큰 겹침 점수로 상위 N 반환.
 *
 * prod 의 OpenSearchAdapter 와 같은 port 계약을 따르되, 외부 인프라 없이 같은 "정신"을 구현한다.
 * collab.search.engine 이 없거나 'memory' 면 이 빈이 활성화된다(matchIfMissing=true).
 */
@Component
@ConditionalOnProperty(name = ["collab.search.engine"], havingValue = "memory", matchIfMissing = true)
class InMemorySearchAdapter : DocumentSearchPort {

    private data class Indexed(val document: Document, val tokenCounts: Map<String, Int>)

    private val index = ConcurrentHashMap<DocumentId, Indexed>()

    override fun index(document: Document) {
        val counts = tokenCounts(document.title + " " + document.content)
        index[document.id] = Indexed(document, counts)
    }

    override fun search(query: String, ownerId: UserId, limit: Int): List<DocumentSearchHit> {
        val terms = tokens(query)
        if (terms.isEmpty()) return emptyList()
        return index.values.asSequence()
            .filter { it.document.ownerId == ownerId }
            .map { it.document to score(terms, it.tokenCounts) }
            .filter { it.second > 0.0 }
            .sortedWith(compareByDescending<Pair<Document, Double>> { it.second }.thenBy { it.first.id.value })
            .take(limit)
            .map { DocumentSearchHit(it.first.id, it.first.title, it.second) }
            .toList()
    }

    override fun searchChunks(documentId: DocumentId, query: String, chunks: List<DocumentChunk>, limit: Int): List<ChunkHit> {
        val terms = tokens(query)
        if (terms.isEmpty()) return emptyList()
        return chunks.asSequence()
            .map { ChunkHit(it, score(terms, tokenCounts(it.text))) }
            .filter { it.score > 0.0 }
            .sortedWith(compareByDescending<ChunkHit> { it.score }.thenBy { it.chunk.ordinal })
            .take(limit)
            .toList()
    }

    /** 질의 토큰 중 문서에 등장한 것의 tf(빈도 로그 가중) 합 = 단순 키워드 스코어. */
    private fun score(queryTerms: Set<String>, docCounts: Map<String, Int>): Double {
        var s = 0.0
        for (t in queryTerms) {
            val tf = docCounts[t] ?: continue
            s += 1.0 + Math.log(tf.toDouble())
        }
        return s
    }

    private fun tokenCounts(s: String): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (t in tokenList(s)) m[t] = (m[t] ?: 0) + 1
        return m
    }

    private fun tokens(s: String): Set<String> = tokenList(s).toSet()

    private fun tokenList(s: String): List<String> =
        s.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }
}
