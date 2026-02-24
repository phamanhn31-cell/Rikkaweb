FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /src

COPY gradle/ gradle/
COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY standalone-server/build.gradle.kts standalone-server/build.gradle.kts
COPY standalone-server/src/ standalone-server/src/

RUN ./gradlew :standalone-server:rikkawebJar --no-daemon \
  -Dkotlin.incremental=false \
  -Dkotlin.compiler.execution.strategy=in-process

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY --from=build /src/standalone-server/build/libs/rikkaweb.jar /app/rikkaweb.jar
COPY docker/entrypoint.sh /app/entrypoint.sh

RUN chmod +x /app/entrypoint.sh \
  && mkdir -p /data

EXPOSE 11001
VOLUME ["/data"]

ENV RIKKAHUB_HOST=0.0.0.0 \
    RIKKAHUB_PORT=11001 \
    RIKKAHUB_DATA_DIR=/data \
    RIKKAHUB_JWT_ENABLED=true

ENTRYPOINT ["/app/entrypoint.sh"]
