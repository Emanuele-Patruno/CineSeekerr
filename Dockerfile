# ---------- Build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies separately from sources for fast rebuilds
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests run in CI; the image build only packages
RUN mvn -B -q package -DskipTests

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S cineseekerr && adduser -S cineseekerr -G cineseekerr
# Holds the pending-downloads state file (see DOWNLOAD_STATE_FILE); mount a volume here
# so a container recreation doesn't lose track of in-flight downloads.
RUN mkdir -p /data && chown cineseekerr:cineseekerr /data
USER cineseekerr
WORKDIR /app

COPY --from=build /build/target/cineseekerr-*.jar app.jar

# Container-aware JVM sizing; the bot is tiny, keep the footprint small
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
