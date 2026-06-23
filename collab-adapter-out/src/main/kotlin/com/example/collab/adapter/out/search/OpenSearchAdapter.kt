package com.example.collab.adapter.out.search

import com.example.collab.application.port.out.ChunkHit
import com.example.collab.application.port.out.DocumentSearchHit
import com.example.collab.application.port.out.DocumentSearchPort
import com.example.collab.domain.document.Document
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import com.example.collab.domain.rag.DocumentChunk
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * prod 검색 어댑터 스켈레톤. collab.search.engine=opensearch 일 때만 활성화된다.
 *
 * 의도: BM25 문서 색인 + (임베딩 hybrid) 청크 검색을 OpenSearch 로 수행.
 * 본 학습 레포는 dev 무의존 부팅이 1순위이므로 실제 OpenSearch 클라이언트 연동은
 * 자리만 잡아 두고(스켈레톤), 활성화 시 명시적으로 실패시켜 "미구현"을 정직하게 드러낸다.
 * Docker 가 떠 있는 prod 환경에서 실 클라이언트(opensearch-java)로 채우는 자리.
 */
@Component
@ConditionalOnProperty(name = ["collab.search.engine"], havingValue = "opensearch")
class OpenSearchAdapter : DocumentSearchPort {

    private val log = LoggerFactory.getLogger(javaClass)

    init {
        log.warn("OpenSearchAdapter active (collab.search.engine=opensearch) — prod skeleton, wire opensearch-java client here.")
    }

    override fun index(document: Document) =
        throw NotImplementedError("OpenSearchAdapter.index: wire opensearch-java client (prod).")

    override fun search(query: String, ownerId: UserId, limit: Int): List<DocumentSearchHit> =
        throw NotImplementedError("OpenSearchAdapter.search: wire opensearch-java client (prod).")

    override fun searchChunks(documentId: DocumentId, query: String, chunks: List<DocumentChunk>, limit: Int): List<ChunkHit> =
        throw NotImplementedError("OpenSearchAdapter.searchChunks: wire opensearch-java client (prod).")
}
