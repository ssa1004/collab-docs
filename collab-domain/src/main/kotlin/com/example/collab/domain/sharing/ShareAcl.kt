package com.example.collab.domain.sharing

import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId

/** 문서 접근 권한 역할. 권한 강도: OWNER > EDITOR > VIEWER. */
enum class Role {
    OWNER, EDITOR, VIEWER;

    /** 편집(본문 OT/제목/공유 변경) 가능 여부. */
    fun canEdit(): Boolean = this == OWNER || this == EDITOR

    /** 조회 가능 여부(모든 역할은 최소 조회 가능). */
    fun canView(): Boolean = true

    /** 공유/권한 변경 등 소유자 전용 행위 가능 여부. */
    fun canManageSharing(): Boolean = this == OWNER
}

/** 한 사용자에게 부여된 단일 권한. */
data class AclEntry(val userId: UserId, val role: Role)

/**
 * 문서 단위 접근 제어 목록(aggregate).
 *
 * owner 는 항상 OWNER 권한을 가진다(엔트리에 없어도 암묵 OWNER).
 * 권한 판정은 도메인 정책으로 여기 고정하고, application 레이어는 [require*] 헬퍼로 강제한다.
 */
data class ShareAcl(
    val documentId: DocumentId,
    val ownerId: UserId,
    val entries: List<AclEntry> = emptyList(),
) {
    /** 해당 사용자의 유효 역할. owner 우선, 없으면 엔트리, 둘 다 없으면 null(권한 없음). */
    fun roleOf(userId: UserId): Role? =
        if (userId == ownerId) Role.OWNER
        else entries.firstOrNull { it.userId == userId }?.role

    fun canView(userId: UserId): Boolean = roleOf(userId)?.canView() == true
    fun canEdit(userId: UserId): Boolean = roleOf(userId)?.canEdit() == true
    fun canManageSharing(userId: UserId): Boolean = roleOf(userId)?.canManageSharing() == true

    /** 권한 부여/변경(owner 자신은 강등 불가). 같은 사용자 기존 엔트리는 교체. */
    fun grant(userId: UserId, role: Role): ShareAcl {
        require(userId != ownerId) { "cannot change owner's role" }
        val without = entries.filterNot { it.userId == userId }
        return copy(entries = without + AclEntry(userId, role))
    }

    /** 권한 회수. */
    fun revoke(userId: UserId): ShareAcl {
        require(userId != ownerId) { "cannot revoke owner" }
        return copy(entries = entries.filterNot { it.userId == userId })
    }

    fun requireView(userId: UserId) {
        if (!canView(userId)) throw AccessDeniedException(userId, documentId, "view")
    }

    fun requireEdit(userId: UserId) {
        if (!canEdit(userId)) throw AccessDeniedException(userId, documentId, "edit")
    }

    fun requireManageSharing(userId: UserId) {
        if (!canManageSharing(userId)) throw AccessDeniedException(userId, documentId, "manage-sharing")
    }

    companion object {
        fun forOwner(documentId: DocumentId, ownerId: UserId) = ShareAcl(documentId, ownerId)
    }
}

/** 권한 위반. application 레이어가 잡아 403 등으로 매핑한다. */
class AccessDeniedException(
    val userId: UserId,
    val documentId: DocumentId,
    val action: String,
) : RuntimeException("user ${userId.value} denied to $action document ${documentId.value}")
