# syntax=docker/dockerfile:1

# 1단계: Gradle Wrapper로 빌드합니다.
# 의존성 다운로드와 소스 복사를 나눠서 소스만 바뀌었을 때 다시 받지 않게 합니다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

COPY src ./src
# 이미지 빌드 단계에서는 Docker가 필요한 Testcontainers 테스트를 실행할 수 없습니다.
# 테스트는 CI나 개발 장비에서 ./gradlew build로 실행합니다.
RUN ./gradlew --no-daemon bootJar -x test

# 2단계: 실행 이미지
FROM eclipse-temurin:21-jre
WORKDIR /app

# root로 실행하지 않습니다.
RUN useradd --system --create-home --uid 10001 manager

USER manager

COPY --from=build /build/build/libs/monitoring-manager.jar /app/monitoring-manager.jar

EXPOSE 8080

# Container 환경에 맞춰 Heap을 잡습니다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/monitoring-manager.jar"]
