FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q dependency:go-offline
COPY src src
RUN ./mvnw -q clean package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 arbitrage && mkdir -p /app/data /app/logs \
    && chown -R arbitrage:arbitrage /app
COPY --from=build --chown=arbitrage:arbitrage /workspace/target/arbitrage-1.0.0.jar app.jar
USER arbitrage
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
