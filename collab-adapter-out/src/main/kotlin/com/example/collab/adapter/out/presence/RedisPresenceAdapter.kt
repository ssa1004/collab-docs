package com.example.collab.adapter.out.presence

import com.example.collab.adapter.out.persistence.TextOperationJson
import com.example.collab.application.port.out.PresenceEvent
import com.example.collab.application.port.out.PresenceListener
import com.example.collab.application.port.out.PresencePort
import com.example.collab.application.port.out.PresenceUpdate
import com.example.collab.application.port.out.Subscription
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import com.example.collab.domain.edit.TextOperation
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.stereotype.Component
import jakarta.annotation.PreDestroy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * prod presence/fan-out 어댑터: Redis pub/sub(Lettuce) + 로컬 fan-out.
 *
 * collab.presence=redis 일 때만 활성화된다. 발행 이벤트를 JSON 으로 직렬화해
 * 채널 "doc:{documentId}" 로 publish 하고, 같은 채널을 구독해 들어온 메시지를
 * 로컬 구독자(WS 세션)에게 fan-out 한다. 이렇게 여러 백엔드 인스턴스 간에도 같은 문서 방의
 * 편집/presence 가 전파된다(연결 스케일아웃).
 *
 * 이벤트 와이어 형식:
 *  - edit:     {"kind":"edit","op":<TextOperationJson>,"version":<int>}
 *  - presence: {"kind":"presence","userId":<str>,"cursor":<int?>,"selStart":..,"selEnd":..,"typing":<bool>}
 */
@Component
@ConditionalOnProperty(name = ["collab.presence"], havingValue = "redis")
class RedisPresenceAdapter(
    private val redis: StringRedisTemplate,
    connectionFactory: RedisConnectionFactory,
    private val mapper: ObjectMapper,
) : PresencePort, MessageListener {

    private val log = LoggerFactory.getLogger(javaClass)
    private val container = RedisMessageListenerContainer().apply {
        setConnectionFactory(connectionFactory)
        afterPropertiesSet()
        start()
    }
    private val rooms = ConcurrentHashMap<DocumentId, CopyOnWriteArrayList<PresenceListener>>()

    init {
        log.info("RedisPresenceAdapter active (collab.presence=redis).")
    }

    private fun channel(documentId: DocumentId) = "doc:${documentId.value}"

    override fun publishEdit(documentId: DocumentId, transformedOp: TextOperation, newVersion: Int) {
        val node = mapper.createObjectNode()
        node.put("kind", "edit")
        node.set<com.fasterxml.jackson.databind.JsonNode>("op", TextOperationJson.toNode(mapper, transformedOp))
        node.put("version", newVersion)
        redis.convertAndSend(channel(documentId), mapper.writeValueAsString(node))
    }

    override fun publishPresence(documentId: DocumentId, presence: PresenceUpdate) {
        val node = mapper.createObjectNode()
        node.put("kind", "presence")
        node.put("userId", presence.userId.value)
        presence.cursor?.let { node.put("cursor", it) }
        presence.selectionStart?.let { node.put("selStart", it) }
        presence.selectionEnd?.let { node.put("selEnd", it) }
        node.put("typing", presence.typing)
        redis.convertAndSend(channel(documentId), mapper.writeValueAsString(node))
    }

    override fun subscribe(documentId: DocumentId, listener: PresenceListener): Subscription {
        // subscribe 와 unsubscribe-정리를 rooms 모니터로 직렬화한다. 이렇게 안 하면 방이 비는 순간과
        // 재구독이 겹칠 때 채널 리스너가 중복 등록(중복 전송)되거나 고아가 될 수 있다.
        synchronized(rooms) {
            val list = rooms.getOrPut(documentId) {
                container.addMessageListener(this, ChannelTopic(channel(documentId)))
                CopyOnWriteArrayList()
            }
            list.add(listener)
        }
        return Subscription {
            synchronized(rooms) {
                val list = rooms[documentId]
                if (list != null) {
                    list.remove(listener)
                    if (list.isEmpty()) {
                        // 방이 비면 채널 구독과 맵 엔트리를 정리 — 장수명 프로세스에서 무한 누적 방지.
                        rooms.remove(documentId)
                        container.removeMessageListener(this, ChannelTopic(channel(documentId)))
                    }
                }
            }
        }
    }

    /** 컨테이너가 시작한 Redis 구독 스레드를 종료 시 정리(형제 prod 어댑터와 동일). */
    @PreDestroy
    private fun shutdown() = container.stop()

    /** Redis 채널 메시지 수신 → 로컬 구독자 fan-out. */
    override fun onMessage(message: Message, pattern: ByteArray?) {
        val ch = String(message.channel)
        val docId = DocumentId(ch.removePrefix("doc:"))
        val list = rooms[docId] ?: return
        val node = mapper.readTree(message.body)
        val event = when (node.path("kind").asText()) {
            "edit" -> PresenceEvent.Edit(
                docId,
                TextOperationJson.fromNode(node.path("op")),
                node.path("version").asInt(),
            )
            "presence" -> PresenceEvent.Presence(
                docId,
                PresenceUpdate(
                    userId = UserId(node.path("userId").asText()),
                    cursor = node.path("cursor").let { if (it.isMissingNode || it.isNull) null else it.asInt() },
                    selectionStart = node.path("selStart").let { if (it.isMissingNode || it.isNull) null else it.asInt() },
                    selectionEnd = node.path("selEnd").let { if (it.isMissingNode || it.isNull) null else it.asInt() },
                    typing = node.path("typing").asBoolean(false),
                ),
            )
            else -> return
        }
        for (l in list) runCatching { l.onEvent(event) }
    }
}
