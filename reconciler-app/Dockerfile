# 외부 대조 앱 이미지(ADR-012 §4.2.8). 빌드 문맥 = 저장소 루트(루트 Dockerfile과 같다 — `docker build -f reconciler-app/Dockerfile .`).
# 빌드 머신(하네스는 k6 PC — §4.2.8 ①)에 JDK가 없어도 되도록 빌드까지 컨테이너 안에서 한다. 빌드 파일을 먼저 넣어 의존성 층을 캐시한다(settings가 세 프로젝트를 다 포함하므로 main-app 빌드 파일도 넣는다).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY main-app/build.gradle.kts main-app/
COPY reconcile-core/build.gradle.kts reconcile-core/
COPY reconciler-app/build.gradle.kts reconciler-app/
RUN ./gradlew --no-daemon -q :reconciler-app:dependencies > /dev/null
COPY reconcile-core reconcile-core
COPY reconciler-app/src/main reconciler-app/src/main
RUN ./gradlew --no-daemon -q :reconciler-app:bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/reconciler-app/build/libs/reconciler-app-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8103
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
