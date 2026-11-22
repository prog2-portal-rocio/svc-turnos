FROM eclipse-temurin:25-jdk-noble@sha256:4ddc1a8bdaf283421df5487411aff10b797ccf1e06a339041005114c8f489b5c AS build
WORKDIR /build
COPY --chmod=755 mvnw ./mvnw
COPY .mvn/wrapper/maven-wrapper.properties ./.mvn/wrapper/maven-wrapper.properties
COPY pom.xml ./pom.xml
COPY src/main ./src/main
# La suite con Testcontainers se ejecuta por separado, fuera de docker build.
RUN ./mvnw --batch-mode -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre-noble@sha256:f3dd0838e69b881cc382237d5901e74ae3705b1056ad964b2e0e523b174fef99
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 turnos \
    && useradd --system --uid 10001 --gid turnos --home-dir /app turnos
WORKDIR /app
COPY --from=build --chown=turnos:turnos /build/target/svc-turnos-0.0.1-SNAPSHOT.jar ./app.jar
USER turnos:turnos
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
