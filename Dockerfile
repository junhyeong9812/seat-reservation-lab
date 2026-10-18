# 부하 측정용 앱 이미지 (ADR-001). 서버에 JDK가 없어도 되도록 빌드까지 컨테이너 안에서 한다.
# Gradle 다중 프로젝트(task 04b) — 이미지는 main-app만 빌드한다. 빌드 파일을 먼저 넣어 의존성 층을 캐시한다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY main-app/build.gradle.kts main-app/
COPY reconcile-core/build.gradle.kts reconcile-core/
COPY reconciler-app/build.gradle.kts reconciler-app/
RUN ./gradlew --no-daemon -q :main-app:dependencies > /dev/null
COPY main-app/src main-app/src
COPY reconcile-core reconcile-core
RUN ./gradlew --no-daemon -q :main-app:bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/main-app/build/libs/seat-reservation-lab-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
