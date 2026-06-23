// collab-adapter-in — REST + WebSocket 인바운드 어댑터 + Spring Security.
// 의존성은 안쪽(:collab-application)을 향한다. use case 를 호출하고, 와이어(JSON) ↔ 도메인 매핑만 한다.
plugins {
    kotlin("plugin.spring")
}

dependencies {
    implementation(project(":collab-application"))
    implementation(project(":collab-domain"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}
