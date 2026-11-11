FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app
RUN apk add --no-cache wget \
    && addgroup -S banking \
    && adduser -S banking -G banking

COPY --from=build /app/build/libs/*.jar app.jar

USER banking
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
