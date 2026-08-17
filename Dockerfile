FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -q clean package

FROM eclipse-temurin:21-jre
WORKDIR /opt/vk-trigger-bot
COPY --from=build /workspace/target/vk-trigger-bot-0.0.1-SNAPSHOT.jar /opt/vk-trigger-bot/app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/vk-trigger-bot/app.jar"]
