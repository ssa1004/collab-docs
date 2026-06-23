plugins {
    // JDK 17 호스트에서도 toolchain(JDK 21)을 자동 조달하도록 Foojay resolver 적용
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "collab-docs"

include(
    "collab-domain",
    "collab-application",
    "collab-adapter-in",
    "collab-adapter-out",
    "collab-bootstrap",
    "e2e-tests",
)
