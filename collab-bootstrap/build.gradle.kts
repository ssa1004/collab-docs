// collab-bootstrap — Spring Boot main + profile 조립. 모든 모듈을 묶어 실행 가능한 bootJar 를 만든다.
plugins {
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":collab-domain"))
    implementation(project(":collab-application"))
    implementation(project(":collab-adapter-in"))
    implementation(project(":collab-adapter-out"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // 마이그레이션(H2 & Postgres 공통 V1__schema.sql)
    implementation("org.flywaydb:flyway-core")

    // OpenAPI(/v3/api-docs) — 향후 drift 게이트용.
    // Spring Boot 3.5 / Spring Framework 6.2 호환 버전(2.6.x 는 ControllerAdviceBean 시그니처 불일치로 깨짐).
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// 실행 가능한 부트 jar
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = true
    archiveFileName.set("collab-docs.jar")
}
tasks.named<Jar>("jar") { enabled = false }
