package com.example.collab.adapter.`in`.ws

import com.example.collab.adapter.`in`.web.CurrentUser
import com.example.collab.adapter.`in`.web.dto.OperationDto
import com.example.collab.application.ApplyEditCommand
import com.example.collab.application.ApplyEditService
import com.example.collab.application.port.out.PresenceEvent
import com.example.collab.application.port.out.PresencePort
import com.example.collab.application.port.out.PresenceUpdate
import com.example.collab.application.port.out.Subscription
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import com.example.collab.domain.sharing.AccessDeniedException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.ConcurrentHashMap

/**
 * 실시간 협업 WebSocket 핸들러 — 엔드포인트 /ws/documents/{id}.
 *
 * ── 와이어 프로토콜 (모든 메시지는 JSON 텍스트 프레임) ────────────────────────────
 * 클라이언트 → 서버:
 *   1) 편집:   {"type":"edit","op":{<OperationDto>},"baseVersion":<int>}
 *       서버가 ApplyEditService 로 서버 권위 OT 적용 → 변환된 op + newVersion 을 방 전체에 브로드캐스트.
 *   2) presence:{"type":"presence","cursor":<int?>,"selectionStart":<int?>,"selectionEnd":<int?>,"typing":<bool>}
 *       커서/선택/타이핑 상태를 방 전체에 브로드캐스트.
 *
 * 서버 → 클라이언트:
 *   - 접속 직후:  {"type":"welcome","documentId":<str>,"userId":<str>}
 *   - 편집 fanout:{"type":"edit","documentId":<str>,"op":{<OperationDto>},"version":<int>}
 *   - presence:   {"type":"presence","documentId":<str>,"userId":<str>,"cursor":..,"selectionStart":..,"selectionEnd":..,"typing":..}
 *   - ack:        {"type":"ack","op":{<OperationDto>},"version":<int>}   (보낸 클라이언트에게 rebase 결과 확인)
 *   - 오류:       {"type":"error","message":<str>}
 *
 * 설계: 접속 시 PresencePort.subscribe 로 doc-room 을 구독한다(in-memory 또는 redis 어댑터).
 * ApplyEditService.apply 안에서 호출되는 PresencePort.publishEdit 가 같은 room 의 모든 세션으로 fan-out 되어
 * REST 든 WS 든 어디서 편집이 들어와도 모든 WS 세션이 동일하게 변환된 op 를 받는다.
 *
 * userId 는 핸드셰이크 인터셉터(WebSocketSecurityHandshakeInterceptor)가 세션 속성에 심어둔 값을 쓴다.
 */
@Component
class CollabWebSocketHandler(
    private val applyEdit: ApplyEditService,
    private val presence: PresencePort,
    private val mapper: ObjectMapper,
) : TextWebSocketHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 세션별 컨텍스트(문서 id, userId, presence 구독 핸들). */
    private data class SessionCtx(
        val documentId: DocumentId,
        val userId: UserId,
        val subscription: Subscription,
    )

    private val contexts = ConcurrentHashMap<String, SessionCtx>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val documentId = documentIdOf(session)
        if (documentId == null) {
            session.close(CloseStatus.BAD_DATA.withReason("missing document id in path"))
            return
        }
        val userId = userIdOf(session)

        // 이 세션을 doc-room 에 구독: 들어오는 edit/presence 이벤트를 이 WS 세션으로 흘려보낸다.
        val sub = presence.subscribe(documentId) { event ->
            runCatching { sendEvent(session, event) }
        }
        contexts[session.id] = SessionCtx(documentId, userId, sub)

        send(session, mapOf("type" to "welcome", "documentId" to documentId.value, "userId" to userId.value))
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val ctx = contexts[session.id] ?: return
        val node = runCatching { mapper.readTree(message.payload) }.getOrNull()
            ?: return sendError(session, "invalid JSON")

        when (node.path("type").asText()) {
            "edit" -> handleEdit(session, ctx, node)
            "presence" -> handlePresence(ctx, node)
            else -> sendError(session, "unknown message type '${node.path("type").asText()}'")
        }
    }

    private fun handleEdit(session: WebSocketSession, ctx: SessionCtx, node: com.fasterxml.jackson.databind.JsonNode) {
        val opDto = runCatching { mapper.treeToValue(node.path("op"), OperationDto::class.java) }.getOrNull()
            ?: return sendError(session, "invalid 'op'")
        val baseVersion = node.path("baseVersion").asInt(-1)
        if (baseVersion < 0) return sendError(session, "missing/invalid 'baseVersion'")

        try {
            // 서버 권위 OT 적용. 성공 시 ApplyEditService 내부에서 PresencePort.publishEdit 가
            // 이 문서 room 의 모든 구독 세션(자기 자신 포함)으로 변환된 op 를 fan-out 한다.
            val result = applyEdit.apply(
                ApplyEditCommand(ctx.documentId, ctx.userId, opDto.toDomain(), baseVersion),
            )
            // 보낸 클라이언트에게 rebase 결과를 ack 로도 직접 알려준다(자기 op 의 최종 위치 확정).
            send(
                session,
                mapOf(
                    "type" to "ack",
                    "op" to opNode(result.transformedOp),
                    "version" to result.newVersion,
                ),
            )
        } catch (e: AccessDeniedException) {
            sendError(session, "access denied: ${e.action}")
        } catch (e: IllegalArgumentException) {
            sendError(session, e.message ?: "invalid edit")
        } catch (e: Exception) {
            log.warn("edit failed on doc ${ctx.documentId.value}", e)
            sendError(session, "edit failed")
        }
    }

    private fun handlePresence(ctx: SessionCtx, node: com.fasterxml.jackson.databind.JsonNode) {
        val update = PresenceUpdate(
            userId = ctx.userId,
            cursor = node.path("cursor").let { if (it.isMissingNode || it.isNull) null else it.asInt() },
            selectionStart = node.path("selectionStart").let { if (it.isMissingNode || it.isNull) null else it.asInt() },
            selectionEnd = node.path("selectionEnd").let { if (it.isMissingNode || it.isNull) null else it.asInt() },
            typing = node.path("typing").asBoolean(false),
        )
        presence.publishPresence(ctx.documentId, update)
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        contexts.remove(session.id)?.subscription?.close()
    }

    // ---- fan-out → 세션 전송 ------------------------------------------------

    private fun sendEvent(session: WebSocketSession, event: PresenceEvent) {
        val payload = when (event) {
            is PresenceEvent.Edit -> mapOf(
                "type" to "edit",
                "documentId" to event.documentId.value,
                "op" to opNode(event.op),
                "version" to event.version,
            )
            is PresenceEvent.Presence -> mapOf(
                "type" to "presence",
                "documentId" to event.documentId.value,
                "userId" to event.update.userId.value,
                "cursor" to event.update.cursor,
                "selectionStart" to event.update.selectionStart,
                "selectionEnd" to event.update.selectionEnd,
                "typing" to event.update.typing,
            )
        }
        send(session, payload)
    }

    private fun send(session: WebSocketSession, payload: Any) {
        if (!session.isOpen) return
        // WebSocketSession 은 동시 전송에 안전하지 않으므로 세션 단위 직렬화.
        synchronized(session) {
            if (session.isOpen) session.sendMessage(TextMessage(mapper.writeValueAsString(payload)))
        }
    }

    /**
     * op 를 와이어용 JsonNode 로 변환한다. 선언 타입을 sealed 베이스(OperationDto)로 강제해
     * @JsonTypeInfo 의 "type" 판별자(insert/delete/composite)가 항상 포함되게 한다(REST 와 동일 형식).
     */
    private fun opNode(op: com.example.collab.domain.edit.TextOperation): com.fasterxml.jackson.databind.JsonNode =
        mapper.readTree(mapper.writerFor(OperationDto::class.java).writeValueAsString(OperationDto.fromDomain(op)))

    private fun sendError(session: WebSocketSession, message: String) =
        send(session, mapOf("type" to "error", "message" to message))

    // ---- helpers -----------------------------------------------------------

    private fun documentIdOf(session: WebSocketSession): DocumentId? {
        val path = session.uri?.path ?: return null
        val raw = path.substringAfterLast("/ws/documents/", "").substringBefore("/").substringBefore("?")
        return raw.takeIf { it.isNotBlank() }?.let { DocumentId(it) }
    }

    private fun userIdOf(session: WebSocketSession): UserId {
        val attr = session.attributes[WS_USER_ID_ATTR] as? String
        return UserId(attr?.takeIf { it.isNotBlank() } ?: CurrentUser.DEMO_USER_ID)
    }

    companion object {
        const val WS_USER_ID_ATTR = "collab.userId"
    }
}
