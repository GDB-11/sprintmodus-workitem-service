# syntax=docker/dockerfile:1

# ---- Build: Maven (the wrapper's pinned version) on the same JDK major version the service runs on
FROM eclipse-temurin:26-jdk AS build
WORKDIR /build

# common-lib and the db-* jars come from GitHub Packages (.mvn/github-settings.xml). The token is a build SECRET, never an
# argument or a layer:
#   docker build --secret id=github_token,env=GITHUB_TOKEN --build-arg GITHUB_ACTOR=<user> .
ARG LIBS_REPO_URL=https://maven.pkg.github.com/GDB-11/*
ARG GITHUB_ACTOR=GDB-11
ENV LIBS_REPO_URL=${LIBS_REPO_URL} GITHUB_ACTOR=${GITHUB_ACTOR}

COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
# Tests run in CI before the image is built (they need Docker themselves), so the image build only packages.
RUN --mount=type=cache,target=/root/.m2 --mount=type=secret,id=github_token \
    GITHUB_TOKEN="$(cat /run/secrets/github_token)" ./mvnw -B -s .mvn/github-settings.xml -DskipTests package \
 && java -Djarmode=tools -jar target/*.jar extract --layers --destination /extracted \
 && mv /extracted/application/*.jar /extracted/application/app.jar

# ---- Runtime: JRE only, non-root, layers ordered from the least to the most frequently changing
FROM eclipse-temurin:26-jre
RUN groupadd --system --gid 10001 sprintmodus \
 && useradd --system --uid 10001 --gid sprintmodus --no-create-home --shell /usr/sbin/nologin sprintmodus
WORKDIR /app
COPY --from=build --chown=10001:10001 /extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /extracted/application/ ./
COPY --chmod=0555 docker/healthcheck.sh /usr/local/bin/healthcheck

# Nothing secret and nothing environment-specific is baked in: every setting comes from the environment at run time.
ENV PORT=8093
USER 10001
EXPOSE 8093
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 CMD ["healthcheck"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
