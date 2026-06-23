package com.example.collab.adapter.out.persistence

import com.example.collab.application.port.out.EditLogEntry
import com.example.collab.application.port.out.EditLogRepository
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * EditLogRepository out-port 의 JPA 어댑터.
 *
 * 각 적용된 (documentId, committedVersion, op-as-json) 을 append-only 로 저장한다.
 * op 는 TextOperationJson 으로 직렬화되어 op_json 컬럼에 들어간다 — ApplyEditService 가
 * baseVersion 이후 커밋된 동시 op 들을 다시 읽어 transform 할 수 있게 한다.
 */
@Repository
class JpaEditLogRepository(
    private val jpa: EditLogJpaRepository,
    private val mapper: ObjectMapper,
) : EditLogRepository {

    override fun append(entry: EditLogEntry): EditLogEntry {
        jpa.save(
            EditLogEntity(
                id = UUID.randomUUID().toString(),
                documentId = entry.documentId.value,
                authorId = entry.authorId.value,
                opJson = TextOperationJson.toJson(mapper, entry.op),
                committedVersion = entry.committedVersion,
                committedAt = entry.committedAt,
            ),
        )
        return entry
    }

    override fun opsCommittedAfter(documentId: DocumentId, baseVersion: Int): List<EditLogEntry> =
        jpa.findByDocumentIdAndCommittedVersionGreaterThanOrderByCommittedVersionAsc(documentId.value, baseVersion)
            .map { it.toDomain() }

    override fun history(documentId: DocumentId): List<EditLogEntry> =
        jpa.findByDocumentIdOrderByCommittedVersionAsc(documentId.value).map { it.toDomain() }

    private fun EditLogEntity.toDomain() = EditLogEntry(
        documentId = DocumentId(documentId),
        authorId = UserId(authorId),
        op = TextOperationJson.fromJson(mapper, opJson),
        committedVersion = committedVersion,
        committedAt = committedAt,
    )
}
