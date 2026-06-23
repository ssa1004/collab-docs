// collab-application — use case(@Service) + inbound/outbound port 인터페이스.
// 도메인을 향해 의존하고(=:collab-domain), 트랜잭션/권한/OT 오케스트레이션/RAG 조립을 담당한다.
// 어댑터(REST/JPA/검색/AI 등)는 모르고, 오직 port 인터페이스만 안다.
plugins {
    // @Service/@Transactional 등 Spring 스테레오타입에 all-open 적용(런타임 CGLIB 프록시용).
    // 소스는 그대로 두고 빌드 차원 와이어링만 추가한다.
    kotlin("plugin.spring")
}

dependencies {
    implementation(project(":collab-domain"))

    // @Service / @Transactional 사용을 위한 최소 Spring (BOM 관리, 버전 명시 없음)
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")
    implementation("jakarta.transaction:jakarta.transaction-api")
}
