# syntax=docker/dockerfile:1
FROM sbtscala/scala-sbt:eclipse-temurin-21.0.13_9_1.13.1_2.13.18 AS build
WORKDIR /src

# Resolve dependencies first (cached layer + cache mounts)
COPY build.sbt ./
COPY project/build.properties project/plugins.sbt ./project/
RUN --mount=type=cache,target=/root/.cache/coursier \
    --mount=type=cache,target=/root/.ivy2 \
    --mount=type=cache,target=/root/.sbt \
    sbt -batch update

COPY app ./app
COPY conf ./conf
RUN --mount=type=cache,target=/root/.cache/coursier \
    --mount=type=cache,target=/root/.ivy2 \
    --mount=type=cache,target=/root/.sbt \
    sbt -batch stage

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
# lib/ = app + dependency jars, conf/ = application.conf, routes, logback.xml
COPY --from=build /src/target/universal/stage/lib ./lib
COPY --from=build /src/target/universal/stage/conf ./conf
USER app
EXPOSE 9000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dpidfile.path=/dev/null", \
            "-cp", "/app/conf:/app/lib/*", "play.core.server.ProdServerStart"]
