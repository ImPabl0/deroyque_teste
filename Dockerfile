# ---- Build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Baixa dependências em camada separada para aproveitar cache
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package \
    && cp target/*.jar app.jar

# ---- Runtime ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /workspace/app.jar app.jar

# Memória da JVM proporcional ao limite do contêiner
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

# Health do Actuator: só fica "healthy" com a aplicação no ar e o banco acessível
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health > /dev/null || exit 1

# exec: a JVM vira o PID 1 e recebe o SIGTERM, permitindo o desligamento gracioso
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
