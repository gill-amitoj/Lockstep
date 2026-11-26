# Runtime image for the fraud service. Build the jar first:
#   ./gradlew :fraud-service:bootJar
# then, from the repository root:
#   docker build -t lockstep-fraud fraud-service
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app

COPY build/libs/fraud-service-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
