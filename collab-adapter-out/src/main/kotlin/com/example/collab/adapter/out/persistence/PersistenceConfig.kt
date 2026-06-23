package com.example.collab.adapter.out.persistence

import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * adapter-out 영속 와이어링. JPA 엔티티/리포지토리가 이 패키지에 있으므로 여기서 스캔을 활성화한다.
 * (bootstrap 은 JPA 의존성을 직접 갖지 않아도 됨 — 헥사고날 경계 유지.)
 */
@Configuration
@EnableJpaRepositories(basePackages = ["com.example.collab.adapter.out.persistence"])
@EntityScan(basePackages = ["com.example.collab.adapter.out.persistence"])
class PersistenceConfig
