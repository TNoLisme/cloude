FROM gradle:8.14.3-jdk21-alpine AS build

WORKDIR /app

COPY settings.gradle.kts build.gradle.kts ./
COPY src src

RUN gradle bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app
RUN apk add --no-cache wget \
    && addgroup -S banking \
    && adduser -S banking -G banking

COPY --from=build /app/build/libs/*.jar app.jar

USER banking
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
