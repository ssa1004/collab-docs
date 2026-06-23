package com.example.collab.application

import com.example.collab.application.port.out.DocumentRepository
import com.example.collab.application.port.out.DocumentSearchPort
import com.example.collab.application.port.out.ShareAclRepository
import com.example.collab.domain.document.Document
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.FolderId
import com.example.collab.domain.document.UserId
import com.example.collab.domain.sharing.ShareAcl
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 문서 생성 use case. 새 문서(version 0) + owner ACL 을 만들고 검색 색인까지 같은 트랜잭션에서 처리한다.
 */
@Service
class CreateDocumentService(
    private val documentRepository: DocumentRepository,
    private val aclRepository: ShareAclRepository,
    private val searchPort: DocumentSearchPort,
) {
    @Transactional
    fun create(command: CreateDocumentCommand): Document {
        val id = command.documentId ?: DocumentId.random()
        val document = Document.create(
            id = id,
            ownerId = command.ownerId,
            title = command.title,
            content = command.content,
            folderId = command.folderId,
        )
        val saved = documentRepository.save(document)
        aclRepository.save(ShareAcl.forOwner(saved.id, saved.ownerId))
        searchPort.index(saved)
        return saved
    }
}

data class CreateDocumentCommand(
    val ownerId: UserId,
    val title: String,
    val content: String = "",
    val folderId: FolderId? = null,
    /** 테스트/결정론용 명시 id. null 이면 랜덤. */
    val documentId: DocumentId? = null,
)
