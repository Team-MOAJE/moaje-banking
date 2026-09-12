FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /workspace

ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx768m -Dorg.gradle.daemon=false"

COPY moaje-grpc-contracts ./moaje-grpc-contracts
COPY moaje-banking ./moaje-banking

WORKDIR /workspace/moaje-banking

RUN chmod +x ./gradlew
RUN ./gradlew clean bootJar -x test --no-daemon --max-workers=1

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY --from=builder /workspace/moaje-banking/build/libs/*.jar app.jar

EXPOSE 8080
EXPOSE 9091

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
