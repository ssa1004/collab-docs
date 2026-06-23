package com.example.collab.application.port.out

import com.example.collab.domain.document.DocumentId
import com.example.collab.domain.document.UserId
import com.example.collab.domain.edit.TextOperation

/**
 * 실시간 fan-out out port. 어댑터: Redis pub/sub(prod) / in-memory(dev) / realtime-gateway 중계.
 *
 * 서버가 OT 로 변환·커밋한 op 와 presence(커서/입력 상태)를 협업자에게 퍼뜨린다.
 * subscribe 는 어댑터가 fan-out 할 수 있게 콜백 등록만 모델링한다(전송 구현은 어댑터 책임).
 * 도메인/애플리케이션은 "무엇을 퍼뜨리는가"만 알고 "어떻게"는 모른다.
 */
interface PresencePort {
    /** 변환·커밋된 op 와 새 version 을 문서 구독자에게 브로드캐스트. */
    fun publishEdit(documentId: DocumentId, transformedOp: TextOperation, newVersion: Int)

    /** 커서/선택/타이핑 등 presence 상태 브로드캐스트. */
    fun publishPresence(documentId: DocumentId, presence: PresenceUpdate)

    /**
     * 문서 채널 구독. 어댑터가 들어오는 이벤트를 listener 로 fan-out 한다.
     * 반환 [Subscription] 을 close 하면 구독 해지.
     */
    fun subscribe(documentId: DocumentId, listener: PresenceListener): Subscription
}

/** presence 한 건(어느 사용자가 어디에 있는지). */
data class PresenceUpdate(
    val userId: UserId,
    val cursor: Int?,
    val selectionStart: Int? = null,
    val selectionEnd: Int? = null,
    val typing: Boolean = false,
)

/** 구독자가 받을 이벤트(edit 또는 presence). */
sealed interface PresenceEvent {
    val documentId: DocumentId
    data class Edit(override val documentId: DocumentId, val op: TextOperation, val version: Int) : PresenceEvent
    data class Presence(override val documentId: DocumentId, val update: PresenceUpdate) : PresenceEvent
}

fun interface PresenceListener {
    fun onEvent(event: PresenceEvent)
}

/** 구독 해지 핸들. */
fun interface Subscription : AutoCloseable {
    override fun close()
}
