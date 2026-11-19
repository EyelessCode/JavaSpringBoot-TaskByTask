# =============================================================================
# Stage 1: Build executable JAR
# =============================================================================
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

RUN apk add --no-cache curl unzip

COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && (./mvnw dependency:resolve -B || true)

COPY src src
RUN ./mvnw package -DskipTests -B && cp target/task-by-task-*.jar /workspace/app.jar

# =============================================================================
# Stage 2: Minimal non-root JRE runtime
# =============================================================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S -G app app
COPY --from=builder --chown=app:app /workspace/app.jar /app/app.jar

USER app

ENV SPRING_PROFILES_ACTIVE=prod \
    PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD wget -qO- "http://127.0.0.1:${PORT:-8080}/actuator/health/liveness" | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=${PORT:-8080} -jar /app/app.jar"]
