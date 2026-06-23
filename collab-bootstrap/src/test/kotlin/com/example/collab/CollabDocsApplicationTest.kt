package com.example.collab

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

/**
 * 컨텍스트 로드 스모크 테스트.
 * 기본(프로필 없음 = zero-infra) 으로 전체 빈 그래프가 떠야 한다: H2 + Flyway + in-memory 어댑터 + offline AI.
 * 외부 인프라 0 으로 동작함을 CI 에서 보장한다.
 */
@SpringBootTest
class CollabDocsApplicationTest {

    @Test
    fun contextLoads() {
        // 컨텍스트가 떴다는 것 자체가 검증. 여기 도달하면 Flyway 마이그레이션 + 빈 와이어링 성공.
    }
}
