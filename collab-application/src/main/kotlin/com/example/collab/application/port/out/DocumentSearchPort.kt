package com.example.collab.application.port.out

import com.example.collab.domain.document.Document
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import com.example.collab.domain.rag.DocumentChunk

/**
 * 문서 검색/색인 out port. 어댑터: OpenSearch(prod) / in-memory 키워드(dev).
 *
 * index 는 문서 단위 색인이고, searchChunks 는 RAG 검색을 위해 청크 단위 키워드 매칭을 돌려준다.
 */
interface DocumentSearchPort {
    /** 문서를 (재)색인. 생성/편집 시 호출. */
    fun index(document: Document)

    /** ownerId 가 접근 가능한 문서 중 query 매칭 상위 결과. */
    fun search(query: String, ownerId: UserId, limit: Int = 10): List<DocumentSearchHit>

    /**
     * RAG 검색: 주어진 문서의 청크들 중 query 와 가장 관련 높은 것 상위 [limit].
     * dev 어댑터는 키워드 겹침 점수, prod 는 임베딩 유사도 + 키워드 하이브리드로 구현 가능.
     */
    fun searchChunks(documentId: DocumentId, query: String, chunks: List<DocumentChunk>, limit: Int = 4): List<ChunkHit>
}

data class DocumentSearchHit(val documentId: DocumentId, val title: String, val score: Double)

data class ChunkHit(val chunk: DocumentChunk, val score: Double)
