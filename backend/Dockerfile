FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /source
COPY backend/ backend/
COPY contracts/effective/ contracts/effective/
COPY reference/v1.43/개발자료/09_구현설계/contracts/ reference/v1.43/개발자료/09_구현설계/contracts/
COPY infra/sql/ infra/sql/
WORKDIR /source/backend
RUN bash gradlew bootJar --no-daemon --max-workers=2 \
    -Dorg.gradle.jvmargs="-Xmx512m -XX:MaxMetaspaceSize=384m" \
    -Pkotlin.compiler.execution.strategy=in-process
FROM eclipse-temurin:17-jre-jammy
RUN groupadd -g 10001 parking && useradd -u 10001 -g parking -m parking
WORKDIR /app
COPY --from=build /source/backend/build/libs/parking-backend-0.1.0-SNAPSHOT.jar /app/app.jar
COPY infra/handoff/bootstrap.sh /app/bootstrap.sh
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx512m", "-jar", "/app/app.jar"]
