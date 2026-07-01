// collab-adapter-out — out-port 구현체(영속/검색/presence/AI/blob).
// 의존성은 안쪽(:collab-application)을 향한다. 기본은 zero-infra(H2/in-memory/offline),
// prod 프로필 어댑터는 @Profile/@ConditionalOnProperty 로 게이트한다.
plugins {
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
}

dependencies {
    implementation(project(":collab-application"))
    implementation(project(":collab-domain"))

    // 영속: Spring Data JPA (H2 default / Postgres prod)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")

    // JSON 직렬화(TextOperation wire/저장 코덱)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // prod 게이트 어댑터(존재만 하면 됨; dev 에서는 빈 미생성)
    implementation("org.springframework.boot:spring-boot-starter-data-redis") // RedisPresenceAdapter (collab.presence=redis)
    implementation("org.springframework.boot:spring-boot-starter-webflux")    // HttpLlmAdapter WebClient (collab.ai=llm)

    // prod 검색 어댑터: OpenSearchAdapter (collab.search.engine=opensearch).
    // opensearch-java + Apache HttpClient5 transport. httpclient5 버전은 Spring Boot BOM 이 관리(미명시).
    implementation("org.opensearch.client:opensearch-java:3.9.0")
    implementation("org.apache.httpcomponents.client5:httpclient5")

    // prod blob 어댑터: S3BlobAdapter (collab.blob=s3). AWS SDK v2 S3, 버전은 awssdk BOM 으로 정렬.
    implementation(platform("software.amazon.awssdk:bom:2.46.20"))
    implementation("software.amazon.awssdk:s3")

    // @ConditionalOnProperty 등 부트 오토컨피그 심볼
    implementation("org.springframework.boot:spring-boot-autoconfigure")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
