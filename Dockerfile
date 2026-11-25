# syntax=docker/dockerfile:1
# Multi-stage build: Angular -> Spring Boot (Gradle Wrapper) -> slim Java 21 runtime.
# Tests are NOT run here (run ./gradlew test before deploying; Testcontainers needs Docker).

# ---- 1. Angular production build ----
FROM node:24-alpine AS frontend
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npx ng build --configuration production

# ---- 2. Spring Boot jar ----
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /app
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts ./
COPY backend/gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY backend/src ./src
# Bundle the Angular build into Spring Boot static resources (one container, one URL).
COPY --from=frontend /app/dist/frontend/browser/ ./src/main/resources/static/
RUN ./gradlew --no-daemon bootJar && cp build/libs/*-SNAPSHOT.jar /app/app.jar

# ---- 3. Runtime ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=backend /app/app.jar ./app.jar
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
