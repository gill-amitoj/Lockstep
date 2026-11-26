# Runtime image for the ledger service. Build the jar first:
#   ./gradlew :ledger-service:bootJar
# then, from the repository root:
#   docker build -t lockstep-ledger ledger-service
FROM eclipse-temurin:21-jre-alpine

# Don't run as root inside the container.
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app

COPY build/libs/ledger-service-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
