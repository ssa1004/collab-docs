package com.example.collab.application

import com.example.collab.application.authz.DocumentAuthorizer
import com.example.collab.application.port.out.DocumentRepository
import com.example.collab.application.port.out.DocumentSearchPort
import com.example.collab.application.port.out.EditLogEntry
import com.example.collab.application.port.out.EditLogRepository
import com.example.collab.application.port.out.PresencePort
import com.example.collab.domain.document.Document
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import com.example.collab.domain.edit.OperationalTransform
import com.example.collab.domain.edit.TextOperation
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 서버 권위 동시 편집의 심장.
 *
 * 클라이언트는 `(op, baseVersion)` 을 보낸다. baseVersion 은 "이 op 를 만들 때 클라이언트가 보고 있던 문서 버전"이다.
 * 서버는 그 사이 다른 사용자가 커밋한 동시 op 들에 대해 들어온 op 를 inclusion-transform 으로 rebase 한 뒤
 * 현재 문서에 적용한다. 그래서 어떤 순서로 동시 편집이 들어와도 모든 클라이언트가 같은 결과로 수렴한다.
 *
 * 동시성 설계:
 *  1) doc + ACL 로드(edit 권한 필요). baseVersion 검증(0..현재 버전).
 *  2) EditLog 에서 baseVersion "초과" 커밋된 동시 op 들을 커밋 순서대로 가져온다.
 *     - baseVersion == 현재 버전 → 동시성 없음(리스트 비어 op 그대로 적용).
 *     - baseVersion < 현재 버전 → 클라이언트가 뒤처짐. 그 op 들에 대해 순서대로 transform.
 *  3) 들어온 op 를 각 동시 op 에 대해 transform(incoming, committed, aHasPriority=false) 로 rebase.
 *     이미 커밋된 쪽이 우선이므로 tie-break 는 항상 committed 쪽이 이긴다(결정론).
 *  4) rebase 된 op 를 doc 에 적용 → newVersion = 현재 버전 + 1.
 *  5) doc 저장 + EditLog append(committedVersion = newVersion) + 검색 재색인 + Presence 브로드캐스트.
 *  6) 변환된 op + newVersion 반환(클라이언트/게이트웨이가 이걸 ack 로 받아 자기 상태를 맞춘다).
 *
 * @Transactional: doc 저장 / EditLog append 를 원자적으로. (낙관적 잠금/직렬화는 어댑터 책임 범위.)
 */
@Service
class ApplyEditService(
    private val documentRepository: DocumentRepository,
    private val editLog: EditLogRepository,
    private val presence: PresencePort,
    private val searchPort: DocumentSearchPort,
    private val authorizer: DocumentAuthorizer,
) {
    @Transactional
    fun apply(command: ApplyEditCommand): ApplyEditResult {
        authorizer.requireEdit(command.documentId, command.editorId)

        val document = documentRepository.load(command.documentId)
        require(command.baseVersion in 0..document.version) {
            "baseVersion ${command.baseVersion} out of range (doc version ${document.version})"
        }

        // 1) 클라이언트가 못 본 동시 op 들(커밋 순서)
        val concurrentOps = editLog.opsCommittedAfter(command.documentId, command.baseVersion)

        // 2) 들어온 op 를 동시 op 들에 대해 차례로 rebase. 들어온 쪽은 우선권 없음(false).
        var rebased: TextOperation = command.op
        for (entry in concurrentOps) {
            rebased = OperationalTransform.transform(rebased, entry.op, aHasPriority = false)
        }

        // 3) 적용 → 새 버전
        val edited: Document = document.applyEdit(rebased)
        val saved = documentRepository.save(edited)

        // 4) 로그 append + 색인 + 브로드캐스트
        editLog.append(
            EditLogEntry(
                documentId = saved.id,
                authorId = command.editorId,
                op = rebased,
                committedVersion = saved.version,
            ),
        )
        searchPort.index(saved)
        presence.publishEdit(saved.id, rebased, saved.version)

        return ApplyEditResult(transformedOp = rebased, newVersion = saved.version)
    }
}

/**
 * @param baseVersion 클라이언트가 op 를 만들 때 보고 있던 문서 버전.
 */
data class ApplyEditCommand(
    val documentId: DocumentId,
    val editorId: UserId,
    val op: TextOperation,
    val baseVersion: Int,
)

/** @param transformedOp 실제 적용된(rebase 된) op — 브로드캐스트/ack 대상. */
data class ApplyEditResult(
    val transformedOp: TextOperation,
    val newVersion: Int,
)
