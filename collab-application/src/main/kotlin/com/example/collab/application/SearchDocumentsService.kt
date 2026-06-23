package com.example.collab.application

import com.example.collab.application.port.out.DocumentSearchHit
import com.example.collab.application.port.out.DocumentSearchPort
import com.example.collab.domain.document.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 문서 검색 use case. 색인은 생성/편집 시 각 서비스가 DocumentSearchPort.index 로 갱신한다
 * (CreateDocumentService, ApplyEditService 참고). 여기선 ownerId 범위 검색만 담당.
 */
@Service
class SearchDocumentsService(
    private val searchPort: DocumentSearchPort,
) {
    @Transactional(readOnly = true)
    fun search(query: String, ownerId: UserId, limit: Int = 10): List<DocumentSearchHit> {
        if (query.isBlank()) return emptyList()
        return searchPort.search(query, ownerId, limit)
    }
}
