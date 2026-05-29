# syntax=docker/dockerfile:1.7

# ---- Stage 1: build ----
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Cache dependencies layer
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

# Source
COPY src/ src/
RUN ./mvnw -q -B -DskipTests package && \
    mkdir -p target/extracted && \
    java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

# ---- Stage 2: runtime ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app app && \
    apk add --no-cache curl tzdata
USER app
WORKDIR /app

COPY --from=builder --chown=app:app /workspace/target/extracted/dependencies/ ./
COPY --from=builder --chown=app:app /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=app:app /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=app:app /workspace/target/extracted/application/ ./

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"
# 8080 = public API (ingress); 8081 = management actuator (scrape + probes,
# API-key gated by SecurityConfig). Operators expose 8080 to client traffic
# and 8081 only to the internal observability + orchestrator network.
EXPOSE 8080 8081
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:8081/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
