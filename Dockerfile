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
# It only works if there IS a limit: compose.yaml sets mem_limit per replica.
# Without that limit the percentage is of the whole Docker VM, and three replicas
# were OOM-killed by the VM's kernel at 2,000 VUs (Pass 13). 70 %, not 75 %,
# leaves ~460 MiB of a 1.5 GiB limit for metaspace, thread stacks and the Netty
# buffers Lettuce and Tomcat allocate off-heap.
#
# The collector is G1, and it is named EXPLICITLY. Under 1,792 MB the JVM stops
# treating the container as a "server-class machine" and silently picks SerialGC,
# a single-threaded stop-the-world collector: exactly wrong for a server carrying
# thousands of virtual threads. The 1.5 GiB limit crosses that line, so leaving
# it to ergonomics would have swapped collectors without anyone noticing. An
# earlier version of this line asked for ZGC, but compose.yaml overrode the whole
# variable, so ZGC never ran in the cluster: every measurement in 06 section 11 is
# G1. G1 is kept so that those numbers stay comparable.
#
# No thread-pool tuning: Java 21 virtual threads (spring.threads.virtual.enabled)
# are what carry concurrent requests and the long-lived SSE connections.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=6 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
