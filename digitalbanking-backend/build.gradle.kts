plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.simulatedbank"
version = "0.1.0-SNAPSHOT"
description = "Digital Banking modular monolith backend"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)

    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    implementation(libs.bouncycastle.prov)
    implementation(libs.jcs)

    implementation(libs.mapstruct)
    compileOnly(libs.lombok)
    annotationProcessor(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.lombok)
    annotationProcessor(libs.lombok.mapstruct.binding)
    annotationProcessor(libs.mapstruct.processor)

    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
    testAnnotationProcessor(libs.lombok.mapstruct.binding)
    testAnnotationProcessor(libs.mapstruct.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.awaitility)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.wiremock.standalone)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            "-parameters",
            "-Amapstruct.defaultComponentModel=spring",
            "-Amapstruct.unmappedTargetPolicy=ERROR"
        )
    )
}

tasks.withType<Test> {
    // Readiness tests launch the exact packaged application, not the test classpath.
    dependsOn(tasks.named("bootJar"))
    systemProperty("readiness.jar", layout.buildDirectory.file("libs/${project.name}-${project.version}.jar").get().asFile.absolutePath)
    useJUnitPlatform()
    System.getenv("DOCKER_HOST")?.let { systemProperty("docker.host", it) }
    val dockerApiVersion = System.getenv("DOCKER_API_VERSION") ?: "1.44"
    systemProperty("api.version", dockerApiVersion)
    environment("DOCKER_API_VERSION", dockerApiVersion)
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.named("check") {
    dependsOn(tasks.named("test"), tasks.named("jacocoTestReport"))
}
