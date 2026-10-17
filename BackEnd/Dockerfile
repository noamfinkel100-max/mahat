FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    --mount=type=secret,id=java_cacerts,target=/opt/java/openjdk/lib/security/cacerts \
    mvn --batch-mode --no-transfer-progress dependency:go-offline
COPY src src
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    --mount=type=secret,id=java_cacerts,target=/opt/java/openjdk/lib/security/cacerts \
    mvn --batch-mode --no-transfer-progress -DskipTests package dependency:copy-dependencies \
    -DincludeScope=runtime -DoutputDirectory=target/dependency

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 cyphernod && useradd --uid 10001 --gid 10001 --create-home cyphernod
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/classes /app/classes
COPY --from=build --chown=10001:10001 /build/target/dependency /app/lib
RUN mkdir /app/uploads && chown 10001:10001 /app/uploads
USER 10001:10001
EXPOSE 12000
CMD ["java", "-cp", "/app/classes:/app/lib/*", "org.mahat.Loader"]
