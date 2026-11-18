# ADR-017 흉내 PG 이미지. 빌드 문맥 = 저장소 루트(`docker build -f mock-pg/Dockerfile .`) — 빌드까지 컨테이너 안에서(루트 Dockerfile과 같다).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY main-app/build.gradle.kts main-app/
COPY reconcile-core/build.gradle.kts reconcile-core/
COPY reconciler-app/build.gradle.kts reconciler-app/
COPY mock-pg/build.gradle.kts mock-pg/
RUN ./gradlew --no-daemon -q :mock-pg:dependencies > /dev/null
COPY mock-pg/src/main mock-pg/src/main
RUN ./gradlew --no-daemon -q :mock-pg:bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/mock-pg/build/libs/mock-pg-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8104
# 설정 파일을 두지 않는다(main-app 테스트 클래스패스의 application.yml과 겹치지 않게) — 포트 · 승인 유효 시간은 인자로
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--server.port=8104"]
