# Se construye desde la raíz del repo (contexto "."), porque el servicio hereda del pom raíz:
#   docker build -f services/inventory/Dockerfile -t urbanmove-inventory .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl services/inventory -am -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 app
WORKDIR /app
COPY --from=build /workspace/services/inventory/target/inventory-*.jar app.jar
USER app
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
