package com.example.collab.adapter.`in`.ws

import com.example.collab.adapter.`in`.web.CurrentUser
import org.springframework.context.annotation.Configuration
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.server.HandshakeInterceptor

/**
 * WebSocket 등록: /ws/documents/{id} 에 CollabWebSocketHandler 를 매핑한다.
 *
 * 데모 편의를 위해 모든 origin 을 허용한다(dev). prod 에서는 게이트웨이/리버스 프록시단에서 origin 을 제한한다.
 * 핸드셰이크 인터셉터가 인증 주체(또는 Bearer/쿼리 토큰)에서 userId 를 뽑아 세션 속성에 심는다.
 */
@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val handler: CollabWebSocketHandler,
) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry
            .addHandler(handler, "/ws/documents/{id}")
            .addInterceptors(UserIdHandshakeInterceptor())
            .setAllowedOriginPatterns("*")
    }
}

/**
 * 핸드셰이크 단계에서 userId 를 결정해 세션 속성에 심는다.
 * 우선순위: SecurityContext 인증(prod JWT / dev demo 필터) → Authorization: Bearer <id> → ?userId=<id> → demo.
 */
class UserIdHandshakeInterceptor : HandshakeInterceptor {
    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        val fromSecurity = runCatching { CurrentUser.id().value }
            .getOrDefault(CurrentUser.DEMO_USER_ID)

        val resolved = if (fromSecurity != CurrentUser.DEMO_USER_ID) {
            fromSecurity
        } else {
            val auth = request.headers.getFirst("Authorization")
            val fromBearer = auth?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
            val fromQuery = request.uri.query
                ?.split("&")
                ?.firstOrNull { it.startsWith("userId=") }
                ?.removePrefix("userId=")
            (fromBearer ?: fromQuery)?.takeIf { it.isNotBlank() } ?: CurrentUser.DEMO_USER_ID
        }
        attributes[CollabWebSocketHandler.WS_USER_ID_ATTR] = resolved
        return true
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) {
        // no-op
    }
}
