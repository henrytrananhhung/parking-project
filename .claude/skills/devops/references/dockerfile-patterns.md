# Dockerfile Patterns for Java 21

## Table of Contents
1. [Multi-stage build — Maven](#multi-stage-build--maven)
2. [Multi-stage build — Gradle](#multi-stage-build--gradle)
3. [Distroless variant (minimal attack surface)](#distroless-variant)
4. [Layer optimization tips](#layer-optimization)

---

## Multi-stage build — Maven

```dockerfile
# Stage 1: dependency resolution (cached separately from source changes)
FROM eclipse-temurin:21-jdk-alpine AS deps
WORKDIR /build

COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

# Download deps — this layer is cached until pom.xml changes
RUN ./mvnw dependency:go-offline -q

# Stage 2: build
FROM deps AS build
COPY src ./src

RUN ./mvnw package -DskipTests -q \
    && java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

# Stage 3: runtime — lean image, no JDK
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

WORKDIR /app

# Copy layers in order of change frequency (least → most)
COPY --from=build --chown=appuser:appgroup /build/target/extracted/dependencies/ ./
COPY --from=build --chown=appuser:appgroup /build/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=appuser:appgroup /build/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=appuser:appgroup /build/target/extracted/application/ ./

# JVM tuning for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+ExitOnOutOfMemoryError \
               -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=30s \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
```

---

## Multi-stage build — Gradle

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS deps
WORKDIR /build

COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY buildSrc buildSrc

# Cache Gradle wrapper and deps
RUN ./gradlew dependencies --no-daemon -q

FROM deps AS build
COPY src ./src

RUN ./gradlew bootJar --no-daemon -q \
    && java -Djarmode=layertools -jar build/libs/*.jar extract --destination build/extracted

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
WORKDIR /app

COPY --from=build --chown=appuser:appgroup /build/build/extracted/dependencies/ ./
COPY --from=build --chown=appuser:appgroup /build/build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=appuser:appgroup /build/build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=appuser:appgroup /build/build/extracted/application/ ./

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
```

---

## Distroless Variant

Minimal attack surface — no shell, no package manager. Use when security posture requires it.

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN ./mvnw dependency:go-offline -q
COPY src ./src
RUN ./mvnw package -DskipTests -q \
    && java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

# Distroless: no shell, no OS utilities — much smaller CVE surface
FROM gcr.io/distroless/java21-debian12:nonroot AS runtime
WORKDIR /app

COPY --from=build /build/target/extracted/dependencies/ ./
COPY --from=build /build/target/extracted/spring-boot-loader/ ./
COPY --from=build /build/target/extracted/snapshot-dependencies/ ./
COPY --from=build /build/target/extracted/application/ ./

EXPOSE 8080

# distroless has no sh — use exec form directly
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+ExitOnOutOfMemoryError", \
  "org.springframework.boot.loader.launch.JarLauncher"]
```

---

## Layer Optimization

Spring Boot layertools splits the JAR into layers ordered by change frequency:

```
dependencies/         ← changes only when pom.xml changes (weeks/months)
spring-boot-loader/   ← changes only on Spring Boot version bump
snapshot-dependencies/← changes when -SNAPSHOT deps change
application/          ← changes on every commit (your code)
```

Because Docker caches each `COPY` layer independently, a code-only change
only invalidates the `application/` layer — all other layers hit cache.
Result: rebuild goes from ~3 min to ~15 seconds after the first full build.

**Never do this** — it invalidates all layers on every code change:
```dockerfile
COPY target/my-service.jar app.jar   # entire JAR = no caching benefit
```

**.dockerignore** — always include this:
```
.git
.github
target/
build/
*.md
.mvn/wrapper/maven-wrapper.jar
Dockerfile*
docker-compose*
```
