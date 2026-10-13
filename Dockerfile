# Reproducible backend image. Build from the repository root.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 prudence
WORKDIR /app
COPY --from=build /workspace/target/prudence-*.jar /app/prudence.jar
USER 10001
EXPOSE 8099
ENTRYPOINT ["java", "-jar", "/app/prudence.jar"]
