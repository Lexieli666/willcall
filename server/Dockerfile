# Two-stage build. The build stage downloads dependencies in a layer of its own so that a
# source-only change does not re-resolve the whole dependency graph.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src

COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
RUN ./gradlew --no-daemon dependencies --quiet || true

COPY src src
RUN ./gradlew --no-daemon bootJar -x test --quiet

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

RUN groupadd --system willcall && useradd --system --gid willcall --home /app willcall \
    && apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /src/build/libs/*.jar /app/willcall.jar
RUN chown -R willcall:willcall /app
USER willcall

ARG GIT_COMMIT=unknown
ENV WILLCALL_GIT_COMMIT=${GIT_COMMIT}

# Container-aware heap sizing: the replica sizes in the capacity model are expressed in
# container limits, so the JVM must read them rather than the host's memory.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=60s --retries=20 \
  CMD curl -fsS http://localhost:8080/ready || exit 1

ENTRYPOINT ["java", "-jar", "/app/willcall.jar"]
