FROM gradle:8.11.1-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle build.gradle ./
COPY src ./src
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
EXPOSE 8080
# JVM heap을 컨테이너 mem_limit 기준으로 잡는다. compose에 mem_limit이 있을 때만 의미가 있으며,
# 둘은 반드시 함께 움직인다(limit 없이 %만 올리면 호스트 전체를 heap으로 잡아 호스트 OOM).
# ExitOnOutOfMemoryError: OOM 시 멎지 말고 종료 → restart: unless-stopped로 깨끗이 재기동.
# (부하테스트로 heap dump가 필요하면 배포 env에서 JAVA_TOOL_OPTIONS를 덮어써 -XX:+HeapDumpOnOutOfMemoryError 추가)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
