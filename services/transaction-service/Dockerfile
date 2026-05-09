# syntax=docker/dockerfile:1.6
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle build.gradle gradle.properties* ./
COPY gradle gradle
COPY proto proto
COPY services/transaction-service services/transaction-service
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew :services:transaction-service:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/transaction-service/build/libs/*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:InitialRAMPercentage=25"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
