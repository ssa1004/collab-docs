package com.example.collab.domain.rag

import com.example.collab.domain.document.DocumentId

/**
 * RAG(검색 증강 생성)용 문서 청크.
 *
 * 문서 본문을 순서(ordinal) 있는 조각으로 나눠 임베딩/검색 단위로 쓴다.
 * ordinal 은 0-based, 같은 문서 내에서 본문 등장 순서를 보존한다.
 */
data class DocumentChunk(
    val documentId: DocumentId,
    val ordinal: Int,
    val text: String,
) {
    init {
        require(ordinal >= 0) { "ordinal must be >= 0" }
        require(text.isNotEmpty()) { "chunk text must not be empty" }
    }

    companion object {
        /**
         * 본문을 대략 [targetSize] 글자 단위로, 문단(빈 줄) 경계를 우선 존중해 청크로 분할한다.
         * 결정론(같은 입력 → 같은 청크)이라 offline 모드/테스트에 적합하다.
         * 빈 본문이면 빈 리스트.
         */
        fun chunk(documentId: DocumentId, content: String, targetSize: Int = 280): List<DocumentChunk> {
            if (content.isBlank()) return emptyList()
            require(targetSize > 0) { "targetSize must be > 0" }

            // 1차: 문단 경계로 자르고, 너무 길면 targetSize 로 추가 분할
            val paragraphs = content.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
            val pieces = ArrayList<String>()
            for (p in paragraphs) {
                if (p.length <= targetSize) {
                    pieces += p
                } else {
                    var i = 0
                    while (i < p.length) {
                        pieces += p.substring(i, minOf(i + targetSize, p.length))
                        i += targetSize
                    }
                }
            }
            return pieces.mapIndexed { idx, text -> DocumentChunk(documentId, idx, text) }
        }
    }
}
