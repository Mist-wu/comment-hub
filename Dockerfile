# syntax=docker/dockerfile:1.7
# 一次构建所有模块，每个服务一个运行阶段：docker compose 用 target 选择。
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY common/pom.xml common/
COPY comment-service/pom.xml comment-service/
COPY ai-gateway/pom.xml ai-gateway/
COPY mock-llm/pom.xml mock-llm/
COPY consumer/pom.xml consumer/
COPY datagen/pom.xml datagen/
COPY common/src/main/proto common/src/main/proto
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline -DskipTests || true
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# 运行时用 JDK 而不是 JRE：排障时要在容器里用 jstack / jstat / jmap
FROM eclipse-temurin:21-jdk AS runtime
WORKDIR /app
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=70 -Xlog:gc*:file=/logs/gc-%p.log:time,uptime,level,tags:filecount=5,filesize=20m"
RUN mkdir -p /logs
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

FROM runtime AS comment-service
COPY --from=build /src/comment-service/target/comment-service-*.jar /app/app.jar
FROM runtime AS ai-gateway
COPY --from=build /src/ai-gateway/target/ai-gateway-*.jar /app/app.jar
FROM runtime AS mock-llm
COPY --from=build /src/mock-llm/target/mock-llm-*.jar /app/app.jar
FROM runtime AS consumer
COPY --from=build /src/consumer/target/consumer-*.jar /app/app.jar
FROM runtime AS datagen
COPY --from=build /src/datagen/target/datagen-*.jar /app/app.jar
