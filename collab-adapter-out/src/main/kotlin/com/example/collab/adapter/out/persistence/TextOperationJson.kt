package com.example.collab.adapter.out.persistence

import com.example.collab.domain.edit.TextOperation
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * TextOperation(sealed) ↔ JSON 코덱.
 *
 * EditLog 저장(op-as-json)과 어댑터 경계 직렬화에 쓰는 안정적 표현. 도메인 타입은
 * Jackson 다형성 어노테이션을 두지 않으므로(프레임워크 무의존), 여기서 명시적으로 인코딩한다.
 *
 * 와이어 형식:
 *  - insert:    {"type":"insert","position":<int>,"text":<string>}
 *  - delete:    {"type":"delete","position":<int>,"length":<int>}
 *  - composite: {"type":"composite","ops":[<op>, ...]}
 */
object TextOperationJson {

    fun toNode(mapper: ObjectMapper, op: TextOperation): ObjectNode {
        val node = mapper.createObjectNode()
        when (op) {
            is TextOperation.Insert -> {
                node.put("type", "insert")
                node.put("position", op.position)
                node.put("text", op.text)
            }
            is TextOperation.Delete -> {
                node.put("type", "delete")
                node.put("position", op.position)
                node.put("length", op.length)
            }
            is TextOperation.Composite -> {
                node.put("type", "composite")
                val arr: ArrayNode = node.putArray("ops")
                op.ops.forEach { arr.add(toNode(mapper, it)) }
            }
        }
        return node
    }

    fun fromNode(node: JsonNode): TextOperation {
        return when (val type = node.path("type").asText()) {
            "insert" -> TextOperation.Insert(
                position = node.path("position").asInt(),
                text = node.path("text").asText(),
            )
            "delete" -> TextOperation.Delete(
                position = node.path("position").asInt(),
                length = node.path("length").asInt(),
            )
            "composite" -> TextOperation.Composite(
                ops = node.path("ops").map { fromNode(it) },
            )
            else -> throw IllegalArgumentException("unknown TextOperation type: '$type'")
        }
    }

    fun toJson(mapper: ObjectMapper, op: TextOperation): String =
        mapper.writeValueAsString(toNode(mapper, op))

    fun fromJson(mapper: ObjectMapper, json: String): TextOperation =
        fromNode(mapper.readTree(json))
}
