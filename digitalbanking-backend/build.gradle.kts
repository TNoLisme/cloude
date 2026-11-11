plugins {
    java
    id("org.springframework.boot") version "3.5.7"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.simulatedbank"
version = "0.1.0-SNAPSHOT"

description = "Digital Banking modular monolith backend"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.withType<Test> {
    useJUnitPlatform()
    System.getenv("DOCKER_HOST")?.let { systemProperty("docker.host", it) }
    System.getenv("DOCKER_API_VERSION")?.let {
        systemProperty("api.version", it)
        environment("DOCKER_API_VERSION", it)
    }
}

tasks.named("check") {
    dependsOn(tasks.named("test"))
}
