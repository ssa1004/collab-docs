package com.example.collab.adapter.`in`.web.dto

import com.example.collab.domain.edit.TextOperation
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * TextOperation 의 와이어(JSON) 표현. REST/WS 경계에서만 쓰고, 도메인 타입과는 [toDomain]/[fromDomain] 으로 변환한다.
 *
 * 와이어 형식(REST body & WS 메시지 공통):
 *  - {"type":"insert","position":<int>,"text":<string>}
 *  - {"type":"delete","position":<int>,"length":<int>}
 *  - {"type":"composite","ops":[<op>, ...]}
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = OperationDto.Insert::class, name = "insert"),
    JsonSubTypes.Type(value = OperationDto.Delete::class, name = "delete"),
    JsonSubTypes.Type(value = OperationDto.Composite::class, name = "composite"),
)
sealed class OperationDto {
    abstract fun toDomain(): TextOperation

    data class Insert(val position: Int, val text: String) : OperationDto() {
        override fun toDomain() = TextOperation.Insert(position, text)
    }

    data class Delete(val position: Int, val length: Int) : OperationDto() {
        override fun toDomain() = TextOperation.Delete(position, length)
    }

    data class Composite(val ops: List<OperationDto>) : OperationDto() {
        override fun toDomain() = TextOperation.Composite(ops.map { it.toDomain() })
    }

    companion object {
        fun fromDomain(op: TextOperation): OperationDto = when (op) {
            is TextOperation.Insert -> Insert(op.position, op.text)
            is TextOperation.Delete -> Delete(op.position, op.length)
            is TextOperation.Composite -> Composite(op.ops.map { fromDomain(it) })
        }
    }
}
