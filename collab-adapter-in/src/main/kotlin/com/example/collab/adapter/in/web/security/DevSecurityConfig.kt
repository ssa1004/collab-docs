package com.example.collab.adapter.`in`.web.security

import com.example.collab.adapter.`in`.web.CurrentUser
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.filter.OncePerRequestFilter

/**
 * dev(default, non-prod) 보안 설정 — zero-infra 데모용.
 *
 * IdP 없이 동작한다: 모든 요청을 permitAll 로 두되, 간단한 Bearer 토큰이 있으면 그 값을 userId 로 인증에 채운다
 * (예: `Authorization: Bearer alice` → userId=alice). 토큰이 없으면 고정 demo 사용자로 인증된다.
 * 따라서 CurrentUser 가 항상 안정적인 userId 를 얻고, 여러 사용자를 토큰만 바꿔 흉내낼 수 있다(공유/권한 데모).
 *
 * 운영용 아님 — 서명 검증을 하지 않는다. 실 JWT 검증은 prod 프로필(ProdSecurityConfig).
 */
@Configuration
@Profile("!prod")
class DevSecurityConfig {

    @Bean
    fun devFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }
            authorizeHttpRequests {
                authorize(anyRequest, permitAll)
            }
            addFilterBefore<UsernamePasswordAuthenticationFilter>(DemoBearerAuthFilter())
        }
        return http.build()
    }
}

/**
 * Bearer 토큰의 평문 값을 userId 로 받아들이는 dev 전용 필터(서명 검증 없음).
 * 토큰이 없으면 demo 사용자로 채운다 — 무인증 데모가 곧장 동작하도록.
 */
class DemoBearerAuthFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader("Authorization")
        val userId = if (header != null && header.startsWith("Bearer ")) {
            header.removePrefix("Bearer ").trim().ifBlank { CurrentUser.DEMO_USER_ID }
        } else {
            CurrentUser.DEMO_USER_ID
        }
        val auth = UsernamePasswordAuthenticationToken(
            userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
        SecurityContextHolder.getContext().authentication = auth
        filterChain.doFilter(request, response)
    }
}
