# FlashSeats — application image
#
# Two stages: build with the JDK, run on the JRE. Dependencies resolve in their
# own layer so a source-only change does not re-download the world.

# ---------------------------------------------------------------------------
# Stage 1 — build
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

# Dependency layer: invalidated only when the POM changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

# Source layer.
COPY src/ src/
RUN ./mvnw -B -q -DskipTests package \
 && mv target/*.jar /build/app.jar

# ---------------------------------------------------------------------------
# Stage 2 — runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN apk add --no-cache wget curl \
 && addgroup -S app && adduser -S -G app app

COPY --from=build --chown=app:app /build/app.jar app.jar

USER app
EXPOSE 8080

# MaxRAMPercentage lets the JVM size its heap from the container limit rather
# than the host's memory, which matters when three replicas share one machine.
#
# No thread-pool tuning: Java 21 virtual threads (spring.threads.virtual.enabled)
# are what carry concurrent requests and the long-lived SSE connections.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=6 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
