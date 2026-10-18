plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
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

// Boot 3.5.5 BOM의 Testcontainers 1.21.3은 Docker API 1.32로 요청해 Docker Engine 29(최소 1.44)에서 연결 실패 — 1.21.4로 올린다.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation(project(":reconcile-core"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // ADR-003 Redis 전략(선점 관문 SET NX · 분산락) — Redis는 그 전략일 때만 연결한다
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.redisson:redisson:4.8.0")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// 폴더를 main-app/으로 옮겨도 산출물은 이동 전과 같게 둔다(task 04b — 동작 무변경):
// jar 이름(Dockerfile·하네스) · 매니페스트 Implementation-Title · Kotlin 모듈 이름(META-INF/*.kotlin_module · internal 멤버 JVM 이름)은 기본값이 프로젝트 이름을 따른다.
tasks.bootJar {
    archiveBaseName = "seat-reservation-lab"
    manifest { attributes("Implementation-Title" to "seat-reservation-lab") }
}
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions.moduleName = "seat-reservation-lab"
}
