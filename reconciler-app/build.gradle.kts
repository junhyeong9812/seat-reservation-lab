// 외부 대조 앱(ADR-012) 골격 — 아직 기능 없음. 탐지·복구는 reconcile-core를 의존한다(복사 금지).
plugins {
    kotlin("jvm")
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

dependencies {
    implementation(project(":reconcile-core"))
}
