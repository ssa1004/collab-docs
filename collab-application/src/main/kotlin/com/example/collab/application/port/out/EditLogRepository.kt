package com.example.collab.application.port.out

import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import com.example.collab.domain.edit.TextOperation
import java.time.Instant

/**
 * 적용된 편집 op 로그(불변 append-only). OT 동시성 해결의 핵심 자료원.
 *
 * 각 엔트리는 "이 op 가 어느 version 으로 커밋되었는가"를 기록한다(committedVersion).
 * [opsCommittedAfter] 는 들어오는 op 의 baseVersion 이후 커밋된 동시 op 들을
 * 커밋 순서대로 돌려줘서, ApplyEditService 가 그에 대해 incoming op 를 transform 하게 한다.
 */
interface EditLogRepository {
    /** 변환되어 newVersion 으로 적용된 op 를 append. 반환은 저장된 엔트리. */
    fun append(entry: EditLogEntry): EditLogEntry

    /**
     * baseVersion "초과"(>) committedVersion 을 가진 op 들을 커밋 순서대로 반환.
     * baseVersion == 현재 version 이면 빈 리스트(동시성 없음).
     */
    fun opsCommittedAfter(documentId: DocumentId, baseVersion: Int): List<EditLogEntry>

    /** 문서의 전체 편집 이력(version 오름차순). 버전 목록/되감기 용. */
    fun history(documentId: DocumentId): List<EditLogEntry>
}

/**
 * @param committedVersion 이 op 적용 후 문서 version (= baseVersion 기준 적용 결과 버전).
 *        예: version 4 문서에 op 적용 → committedVersion = 5.
 */
data class EditLogEntry(
    val documentId: DocumentId,
    val authorId: UserId,
    val op: TextOperation,
    val committedVersion: Int,
    val committedAt: Instant = Instant.now(),
)
