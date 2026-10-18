// 탐지·복구 공통 모듈(ADR-012 — main-app과 reconciler-app이 같은 코드를 의존한다). 패키지 이름은 옮겨 와도 그대로 둔다.
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
