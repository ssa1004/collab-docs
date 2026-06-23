package com.example.collab.adapter.`in`.web.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.web.SecurityFilterChain

/**
 * prod 보안 설정 — 실 JWT resource server.
 *
 * 모든 api/ 와 ws/ 경로는 인증 필요(actuator health 와 OpenAPI 는 공개).
 * JWT 검증은 spring.security.oauth2.resourceserver.jwt.issuer-uri 설정으로 게이트된다(application-prod.yml).
 * 토큰의 subject(sub) 가 CurrentUser 에서 userId 로 쓰인다.
 */
@Configuration
@Profile("prod")
class ProdSecurityConfig {

    @Bean
    fun prodFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }
            authorizeHttpRequests {
                authorize("/actuator/health/**", permitAll)
                authorize("/actuator/info", permitAll)
                authorize("/v3/api-docs/**", permitAll)
                authorize("/swagger-ui/**", permitAll)
                authorize(anyRequest, authenticated)
            }
            oauth2ResourceServer {
                jwt { }
            }
        }
        return http.build()
    }
}
