package com.example.collab.domain.document

import java.util.UUID

/** 도메인 식별자 value type 모음. 프레임워크 무관, 단순 래퍼로 타입 안정성 확보. */

@JvmInline
value class DocumentId(val value: String) {
    init { require(value.isNotBlank()) { "DocumentId must not be blank" } }
    companion object { fun random() = DocumentId(UUID.randomUUID().toString()) }
}

@JvmInline
value class UserId(val value: String) {
    init { require(value.isNotBlank()) { "UserId must not be blank" } }
    companion object { fun random() = UserId(UUID.randomUUID().toString()) }
}

@JvmInline
value class FolderId(val value: String) {
    init { require(value.isNotBlank()) { "FolderId must not be blank" } }
    companion object { fun random() = FolderId(UUID.randomUUID().toString()) }
}

@JvmInline
value class CommentId(val value: String) {
    init { require(value.isNotBlank()) { "CommentId must not be blank" } }
    companion object { fun random() = CommentId(UUID.randomUUID().toString()) }
}
