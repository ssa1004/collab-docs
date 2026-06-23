package com.example.collab

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * collab-docs 부트스트랩 진입점.
 *
 * 헥사고날 모듈 전체를 묶는다. scanBasePackages 를 com.example.collab 루트로 두어
 * adapter-in/adapter-out/application 의 @Component/@Service/@Repository/@RestController 가 모두 잡히게 한다.
 * JPA 엔티티/리포지토리 스캔은 adapter-out 의 PersistenceConfig 가 담당한다(JPA 의존성이 거기 있으므로).
 *
 * 기본(프로필 없음) 부팅 = zero-infra: H2 in-memory + in-memory 검색/presence + offline AI.
 * `prod` 프로필에서 Postgres/Redis/OpenSearch/실 LLM 활성화(application-prod.yml).
 */
@SpringBootApplication(scanBasePackages = ["com.example.collab"])
class CollabDocsApplication

fun main(args: Array<String>) {
    runApplication<CollabDocsApplication>(*args)
}
