FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
ARG MAVEN_MIRROR=https://repo.maven.apache.org/maven2
RUN printf '<settings><mirrors><mirror><id>build-mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>' "$MAVEN_MIRROR" > /tmp/maven-settings.xml \
    && mvn -s /tmp/maven-settings.xml -B -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 app && mkdir -p /app/data && chown app:app /app/data
COPY --from=build /app/target/mail-system-0.0.1-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
