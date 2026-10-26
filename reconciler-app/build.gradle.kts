// 외부 대조 앱(ADR-012 §4.2.1 · §4.2.8) — 증분(ⓑⓒ) + 안전망만 돈다. 탐지·복구는 reconcile-core를 의존한다(복사 금지).
// JPA·Redis·Flyway 없음(스키마는 업무 앱이 만든다) — spring-boot-starter-jdbc · actuator(health·configprops·metrics) · PostgreSQL 드라이버.
plugins {
    kotlin("jvm")
    // @Component 클래스를 프록시 가능하게(core·main-app과 같은 컴파일 규칙)
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "com.jun.labs"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// main-app과 같은 이유 — Boot 3.5.5 BOM의 Testcontainers 1.21.3은 Docker Engine 29(최소 API 1.44)에 붙지 못한다
extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation(project(":reconcile-core"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // actuator를 HTTP로 열 웹 서버만 — 앱 자체 엔드포인트는 두지 않는다
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:postgresql")
    // 테스트 스키마만(업무 앱 마이그레이션 파일을 파일 경로로 적용) — 앱 런타임에는 Flyway가 없다
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
