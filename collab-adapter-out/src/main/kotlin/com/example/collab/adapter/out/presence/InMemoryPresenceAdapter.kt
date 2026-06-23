package com.example.collab.adapter.out.presence

import com.example.collab.application.port.out.PresenceEvent
import com.example.collab.application.port.out.PresenceListener
import com.example.collab.application.port.out.PresencePort
import com.example.collab.application.port.out.PresenceUpdate
import com.example.collab.application.port.out.Subscription
import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.edit.TextOperation
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 기본(zero-infra) presence/fan-out 어댑터: 인프로세스 pub/sub.
 *
 * doc-room(DocumentId) -> listener 목록(ConcurrentHashMap + CopyOnWriteArrayList).
 * publishEdit/publishPresence 는 해당 room 의 모든 구독자에게 동기 fan-out 한다.
 * WS 핸들러가 세션마다 subscribe 하고, ApplyEditService 의 publishEdit 가 여기로 들어오면
 * 같은 문서를 보고 있는 모든 세션으로 변환된 op + newVersion 이 푸시된다.
 *
 * 단일 인스턴스 한정(스케일아웃 시 RedisPresenceAdapter 로 교체). 학습/데모용으로 충분.
 */
@Component
@ConditionalOnProperty(name = ["collab.presence"], havingValue = "memory", matchIfMissing = true)
class InMemoryPresenceAdapter : PresencePort {

    private val rooms = ConcurrentHashMap<DocumentId, CopyOnWriteArrayList<PresenceListener>>()

    override fun publishEdit(documentId: DocumentId, transformedOp: TextOperation, newVersion: Int) {
        fanOut(documentId, PresenceEvent.Edit(documentId, transformedOp, newVersion))
    }

    override fun publishPresence(documentId: DocumentId, presence: PresenceUpdate) {
        fanOut(documentId, PresenceEvent.Presence(documentId, presence))
    }

    override fun subscribe(documentId: DocumentId, listener: PresenceListener): Subscription {
        val list = rooms.computeIfAbsent(documentId) { CopyOnWriteArrayList() }
        list.add(listener)
        return Subscription {
            list.remove(listener)
            // room 이 비면 정리(메모리 누수 방지). 경합으로 비어있지 않게 되면 다음 close 가 정리.
            if (list.isEmpty()) rooms.remove(documentId, list)
        }
    }

    private fun fanOut(documentId: DocumentId, event: PresenceEvent) {
        val list = rooms[documentId] ?: return
        for (l in list) {
            // 한 구독자가 던져도 나머지 fan-out 은 계속한다.
            runCatching { l.onEvent(event) }
        }
    }
}
