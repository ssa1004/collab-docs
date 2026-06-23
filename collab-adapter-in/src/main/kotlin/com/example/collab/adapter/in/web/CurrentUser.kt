package com.example.collab.adapter.`in`.web

import com.example.collab.domain.document.UserId
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt

/**
 * 현재 인증 주체에서 userId 를 뽑는다.
 *
 * - JWT(resource server, prod): subject(sub) 클레임을 userId 로 사용.
 * - dev permissive: SecurityConfig 가 고정 demo 사용자로 인증을 채워두므로 그 name 을 쓴다.
 *
 * 인증이 비어 있으면(설정 누락 등) 안전하게 데모 사용자로 폴백한다(dev 무인증 데모 보장).
 */
object CurrentUser {
    const val DEMO_USER_ID = "demo-user"

    fun id(): UserId {
        val auth: Authentication? = SecurityContextHolder.getContext().authentication
        val principal = auth?.principal
        val raw = when {
            principal is Jwt -> principal.subject
            auth != null && auth.name.isNotBlank() && auth.name != "anonymousUser" -> auth.name
            else -> DEMO_USER_ID
        }
        return UserId(raw.ifBlank { DEMO_USER_ID })
    }
}
