package com.example.collab.domain.folder

import com.example.collab.domain.document.FolderId
import com.example.collab.domain.document.UserId

/**
 * 폴더 aggregate. 트리 구조(parentId 로 상위 폴더 참조, null 이면 루트).
 * 순환 방지/이동 정합성은 application 레이어 책임으로 두고, 여기선 불변 데이터만 보장한다.
 */
data class Folder(
    val id: FolderId,
    val ownerId: UserId,
    val name: String,
    val parentId: FolderId? = null,
) {
    init {
        require(name.isNotBlank()) { "folder name must not be blank" }
        require(parentId != id) { "folder cannot be its own parent" }
    }

    val isRoot: Boolean get() = parentId == null

    fun rename(newName: String): Folder = copy(name = newName)
    fun moveUnder(newParent: FolderId?): Folder = copy(parentId = newParent)

    companion object {
        fun create(id: FolderId, ownerId: UserId, name: String, parentId: FolderId? = null) =
            Folder(id, ownerId, name, parentId)
    }
}
