package com.example.collab.domain.document

import com.example.collab.domain.edit.OperationalTransform
import com.example.collab.domain.edit.TextOperation

/**
 * 문서 aggregate root.
 *
 * content 는 하나의 plain-text 문자열, version 은 낙관적 동시성/OT 기준점.
 * [applyEdit] 는 op 를 현재 content 에 적용한 **새 Document(불변)**을 돌려주고 version 을 1 증가시킨다.
 * 동시 편집 충돌 해결(transform)은 application 레이어가 OperationalTransform 으로 수행하고,
 * 이 aggregate 는 "이미 변환되어 현재 버전에 적용 가능한 op" 를 받는다는 계약이다.
 */
data class Document(
    val id: DocumentId,
    val ownerId: UserId,
    val title: String,
    val content: String,
    val version: Int,
    val folderId: FolderId? = null,
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
        require(version >= 0) { "version must be >= 0" }
    }

    /**
     * op 를 현재 content 에 적용한 새 Document 를 돌려준다(version +1).
     * op 가 범위를 벗어나면 OperationalTransform.apply 가 IllegalOperationException 을 던진다.
     */
    fun applyEdit(op: TextOperation): Document =
        copy(content = OperationalTransform.apply(content, op), version = version + 1)

    /** 제목 변경(버전은 올리지 않음 — 본문 OT 버전과 분리). */
    fun rename(newTitle: String): Document = copy(title = newTitle)

    /** 폴더 이동. */
    fun moveTo(folder: FolderId?): Document = copy(folderId = folder)

    companion object {
        /** 새 빈 문서 생성(version 0). */
        fun create(id: DocumentId, ownerId: UserId, title: String, content: String = "", folderId: FolderId? = null) =
            Document(id, ownerId, title, content, version = 0, folderId = folderId)
    }
}
