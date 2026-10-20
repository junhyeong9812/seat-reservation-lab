// 탐지·복구 공통 모듈(ADR-012 — main-app과 reconciler-app이 같은 코드를 의존한다). 패키지 이름은 옮겨 와도 그대로 둔다.
// core는 앱 타입(도메인·JPA·SeatHoldProperties)을 import하지 않는다 — 의존은 spring-jdbc · spring-tx · spring-context · micrometer-core · slf4j뿐(ADR-012 §4.2.1).
plugins {
    kotlin("jvm")
    // @Component 클래스를 main-app에 있을 때와 같이 open으로(프록시 가능 여부를 옮기기 전과 같게)
    kotlin("plugin.spring")
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
    // 부트 플러그인 없이 Spring Boot BOM만 — 버전은 루트의 부트 플러그인 버전(3.5.5) 한 곳에서 온다.
    // io.spring.dependency-management를 쓰지 않는 이유: 부트 플러그인이 없으면 kotlin.version을 맞춰 주지 않아 BOM의 Kotlin 1.9가
    // Kotlin 컴파일러 도구 구성(detached)까지 내려 compileKotlin이 깨진다. Gradle platform은 이 구성의 의존성에만 걸린다.
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework:spring-tx")
    implementation("org.springframework:spring-context")
    implementation("io.micrometer:micrometer-core")
    implementation("org.slf4j:slf4j-api")
}

// main-app과 같은 컴파일 옵션(Spring 널 가능성 애너테이션을 엄격히)
kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}
