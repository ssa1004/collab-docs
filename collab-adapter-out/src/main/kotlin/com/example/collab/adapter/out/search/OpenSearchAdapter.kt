package com.example.collab.adapter.out.search

import com.example.collab.application.port.out.ChunkHit
import com.example.collab.application.port.out.DocumentSearchHit
import com.example.collab.application.port.out.DocumentSearchPort
import com.example.collab.domain.document.Document
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import com.example.collab.domain.rag.DocumentChunk
import jakarta.annotation.PreDestroy
import org.apache.hc.client5.http.auth.AuthScope
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider
import org.apache.hc.core5.http.HttpHost
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch._types.FieldValue
import org.opensearch.client.opensearch._types.Refresh
import org.opensearch.client.transport.OpenSearchTransport
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * prod 검색 어댑터: opensearch-java 클라이언트로 BM25 문서 색인/검색 + 청크 키워드 검색.
 * collab.search.engine=opensearch 일 때만 활성화된다(dev 는 InMemorySearchAdapter — 무인프라).
 *
 * 설정:
 *  - collab.search.opensearch.host     (기본 localhost)
 *  - collab.search.opensearch.port     (기본 9200)
 *  - collab.search.opensearch.scheme   (기본 https)
 *  - collab.search.opensearch.username / password (선택; 보안 플러그인용 basic auth)
 *
 * 인덱스: collab-documents(문서), collab-chunks(RAG 청크).
 * searchChunks 는 호출 시 제공된 청크를 멱등 색인한 뒤 질의해 OpenSearch 의 분석기/스코어링을 그대로 쓴다
 * (임베딩 하이브리드는 임베딩 모델을 주입해 확장 가능 — 현재는 BM25 키워드).
 */
@Component
@ConditionalOnProperty(name = ["collab.search.engine"], havingValue = "opensearch")
class OpenSearchAdapter(
    @Value("\${collab.search.opensearch.host:localhost}") host: String,
    @Value("\${collab.search.opensearch.port:9200}") port: Int,
    @Value("\${collab.search.opensearch.scheme:https}") scheme: String,
    @Value("\${collab.search.opensearch.username:}") username: String,
    @Value("\${collab.search.opensearch.password:}") password: String,
) : DocumentSearchPort {

    private val log = LoggerFactory.getLogger(javaClass)
    private val transport: OpenSearchTransport
    private val client: OpenSearchClient

    init {
        val httpHost = HttpHost(scheme, host, port)
        val builder = ApacheHttpClient5TransportBuilder.builder(httpHost)
        if (username.isNotBlank()) {
            val creds = BasicCredentialsProvider().apply {
                setCredentials(AuthScope(httpHost), UsernamePasswordCredentials(username, password.toCharArray()))
            }
            builder.setHttpClientConfigCallback { hc -> hc.setDefaultCredentialsProvider(creds) }
        }
        transport = builder.build()
        client = OpenSearchClient(transport)
        log.info("OpenSearchAdapter active (collab.search.engine=opensearch, {}://{}:{}).", scheme, host, port)
    }

    override fun index(document: Document) {
        val body = mapOf(
            "title" to document.title,
            "content" to document.content,
            "ownerId" to document.ownerId.value,
        )
        client.index { i -> i.index(DOC_INDEX).id(document.id.value).document(body).refresh(Refresh.True) }
    }

    override fun search(query: String, ownerId: UserId, limit: Int): List<DocumentSearchHit> {
        val resp = client.search({ s ->
            s.index(DOC_INDEX).size(limit).query { q ->
                q.bool { b ->
                    b.must { m -> m.multiMatch { mm -> mm.fields(listOf("title", "content")).query(query) } }
                        .filter { f -> f.term { t -> t.field("ownerId").value(FieldValue.of(ownerId.value)) } }
                }
            }
        }, Map::class.java)

        return resp.hits().hits().mapNotNull { hit ->
            val src = hit.source() ?: return@mapNotNull null
            val id = hit.id() ?: return@mapNotNull null
            DocumentSearchHit(DocumentId(id), src["title"]?.toString() ?: "", hit.score() ?: 0.0)
        }
    }

    override fun searchChunks(documentId: DocumentId, query: String, chunks: List<DocumentChunk>, limit: Int): List<ChunkHit> {
        if (chunks.isEmpty()) return emptyList()
        // 제공된 청크를 멱등 색인(같은 documentId#ordinal id) 후 BM25 로 질의한다.
        for (c in chunks) {
            val body = mapOf("documentId" to documentId.value, "ordinal" to c.ordinal, "text" to c.text)
            client.index { i ->
                i.index(CHUNK_INDEX).id("${documentId.value}#${c.ordinal}").document(body).refresh(Refresh.True)
            }
        }
        val byOrdinal = chunks.associateBy { it.ordinal }

        val resp = client.search({ s ->
            s.index(CHUNK_INDEX).size(limit).query { q ->
                q.bool { b ->
                    b.must { m -> m.match { ma -> ma.field("text").query(FieldValue.of(query)) } }
                        .filter { f -> f.term { t -> t.field("documentId").value(FieldValue.of(documentId.value)) } }
                }
            }
        }, Map::class.java)

        return resp.hits().hits().mapNotNull { hit ->
            val ordinal = (hit.source()?.get("ordinal") as? Number)?.toInt() ?: return@mapNotNull null
            val chunk = byOrdinal[ordinal] ?: return@mapNotNull null
            ChunkHit(chunk, hit.score() ?: 0.0)
        }
    }

    @PreDestroy
    private fun close() = transport.close()

    private companion object {
        const val DOC_INDEX = "collab-documents"
        const val CHUNK_INDEX = "collab-chunks"
    }
}
