# Stage 1: build the fat jar with Maven. Kept separate from the runtime
# image so the final image never carries the JDK, Maven, or the ~/.m2
# dependency cache -- just the jar and a JRE.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy the POM first and resolve dependencies before copying source, so
# Docker's layer cache skips re-downloading the world on every source change.
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src src
RUN mvn package -DskipTests -B

# Stage 2: run. eclipse-temurin's *-jre variant has no compiler/build
# tooling at all, just the JVM -- smaller image, smaller attack surface.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
