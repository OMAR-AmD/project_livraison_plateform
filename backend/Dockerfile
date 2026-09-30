# syntax=docker/dockerfile:1
#
# Backend image: build with Maven, run on a JRE.
#
# Two stages so the ~250 MB of Maven, the JDK toolchain and the dependency
# cache never reach the shipped image. The runtime stage carries the JDK's
# `jre` variant, not the full JDK, because nothing compiles at runtime.
#
# The fraud model (src/main/resources/model/trajectory_fraud_rf.json.gz) rides
# in with `COPY src`, so it is baked into the jar. TrajectoryFraudConfig refuses
# to start without it, which means an image built from a source tree missing the
# model fails at boot instead of quietly serving a platform with no detection.

# ---------------------------------------------------------------- build stage
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# Dependencies resolve before any source file is copied. This layer is cached
# until pom.xml changes, so an ordinary edit to a .java file rebuilds in seconds
# instead of re-downloading the dependency tree. Note the deliberate `||`:
# go-offline is known to miss some plugins, and the fallback re-runs online
# rather than failing the build on a detail that does not matter.
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline || mvn -B -ntp dependency:resolve

COPY src ./src
# Tests are skipped here on purpose: the CI workflow runs `./mvnw package`,
# which includes them, and running the suite twice would double the build for no
# additional signal. A container that shipped only after its tests passed is
# the point of the pipeline, not of this stage.
RUN mvn -B -ntp -DskipTests package

# The jar is the only build output. spring-boot:repackage has already produced
# an executable jar; repackaging again here would nest it twice.
#
# The `ls` below is not decoration: `cp target/*.jar` matches the plain jar as
# well as the executable one when the build has not been repackaged, and a
# glob that matches two files fails with "cp: target ... are the same file".
# Listing afterwards makes the copy failure obvious here instead of surfacing
# later as a container whose jar will not start.
RUN set -eux; \
    cp target/*.jar /build/app.jar; \
    test -s /build/app.jar; \
    ls -l /build/app.jar

# -------------------------------------------------------------- runtime stage
FROM eclipse-temurin:17-jre-jammy AS runtime

# curl is here for the healthcheck below. Without a real HTTP probe the
# container reports healthy the instant the JVM starts, which is before the
# context has connected to Postgres or loaded the fraud model -- an orchestrator
# would send traffic to an instance that is not serving yet.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Unprivileged by default. A container that runs as root turns any RCE in the
# application into root on the host's namespaces, so the user is created here
# rather than passed with --user at run time, where it is easy to forget.
RUN groupadd --system --gid 1001 spring \
 && useradd --system --uid 1001 --gid spring --home /app --shell /usr/sbin/nologin spring

WORKDIR /app

# Copied with ownership rather than chmod-ed afterwards so the running user does
# not need write access to the jar, which it has no reason to hold.
COPY --from=build --chown=spring:spring /build/app.jar ./app.jar

USER spring:spring

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC" \
    SPRING_PROFILES_ACTIVE=demo

# Any HTTP response counts as healthy; a refused connection does not.
#
# curl is deliberately run without -f here. That endpoint answers GET with 405
# because it only accepts POST, and -f turns every 4xx into a failed check --
# the container would report unhealthy forever while working perfectly. The
# signal that actually matters is the socket: Spring Boot starts Tomcat only
# after the context has refreshed, so "something answers on 8080" means the
# database is connected and the fraud model has been loaded. A refused
# connection, by contrast, means the JVM is gone or still starting.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -sS --max-time 4 -o /dev/null http://localhost:8080/api/v1/auth/login || exit 1

# exec form, not a shell: PID 1 becomes the JVM, so SIGTERM from `docker stop`
# reaches the application and Spring runs its shutdown hook instead of being
# killed after the grace period.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]