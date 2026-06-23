package com.example.collab.adapter.`in`.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 활성 AI 모드를 보고한다(정직 표기용). collab.ai 가 없거나 'offline' 이면 offline=true.
 * /summarize 응답의 offline 플래그가 이걸 따른다(ask 는 포트가 직접 offline 을 돌려줌).
 */
@Component
class AiMode(
    @Value("\${collab.ai:offline}") private val mode: String,
) {
    val offline: Boolean get() = mode.equals("offline", ignoreCase = true)
}
