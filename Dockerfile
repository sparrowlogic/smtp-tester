# syntax=docker/dockerfile:1

# ---- build ----------------------------------------------------------------
# The build stage needs a full JDK: the jte-maven-plugin precompiles every template into Java
# classes that are then compiled into the jar. Without that step the running container would look
# for src/main/jte on disk, not find it, and serve 404s for the whole UI.
FROM eclipse-temurin:26-jdk AS build
WORKDIR /build

# Dependencies first, in their own layer, so editing source does not re-download the world.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY checkstyle.xml checkstyle-suppressions.xml ./
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && cp target/*-exec.jar /build/app.jar

# ---- runtime --------------------------------------------------------------
FROM eclipse-temurin:26-jre
LABEL org.opencontainers.image.title="smtp-tester" \
      org.opencontainers.image.description="SMTP testing server with a web inbox, RFC compliance validation and an MCP server" \
      org.opencontainers.image.source="https://github.com/sparrowlogic/smtp-tester" \
      org.opencontainers.image.vendor="Sparrow Logic, Inc." \
      org.opencontainers.image.licenses="MIT"

# Unprivileged by default. The spool directory is created and chowned here rather than at
# startup, because a bind mount over an unowned path is the usual way spooling fails on a
# first `docker run -v`.
RUN groupadd --system --gid 10001 smtp \
    && useradd --system --uid 10001 --gid smtp --home /app --shell /usr/sbin/nologin smtp \
    && mkdir -p /data/mail \
    && chown -R smtp:smtp /data

WORKDIR /app
COPY --from=build --chown=smtp:smtp /build/app.jar /app/app.jar
USER smtp

# 1025 SMTP, 8025 HTTP: inbox UI, REST API and the MCP endpoint at /mcp. Both ports match
# MailHog and Mailpit, so an existing developer setup needs no changes.
EXPOSE 1025 8025
VOLUME ["/data/mail"]

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" \
    SMTP_TESTER_SPOOL_DIRECTORY=/data/mail

# No curl in a JRE image, and spawning a second JVM per check would be absurd; bash's /dev/tcp
# gives a real HTTP round-trip against the inbox API for free.
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8025 \
        && printf "GET /api/v1/inboxes HTTP/1.0\r\n\r\n" >&3 \
        && head -1 <&3 | grep -q " 200 "' 

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
