import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    jacoco
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.9.0"
    id("net.ltgt.errorprone") version "5.1.1"
    id("me.champeau.jmh") version "0.7.3"
}

group = "dev.willcall"
version = "1.0.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

extra["testcontainersVersion"] = "1.21.3"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // Lettuce connection pooling needs commons-pool2 on the classpath; without it Spring's
    // pooled factory fails at start-up with NoClassDefFoundError rather than falling back.
    implementation("org.apache.commons:commons-pool2")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("net.logstash.logback:logstash-logback-encoder:8.1")
    implementation("com.auth0:java-jwt:4.5.0")
    runtimeOnly("org.postgresql:postgresql")

    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    errorprone("com.google.errorprone:error_prone_core:2.50.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("net.jqwik:jqwik:1.9.3")
    // Lets a jqwik @Property run inside a Spring test context, which is what the model-based
    // reservation test needs: it compares the real service against a reference state machine.
    testImplementation("net.jqwik:jqwik-spring:0.12.0")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.awaitility:awaitility")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}")
    }
}

// Integration tests (Testcontainers) live in src/integrationTest and run separately from
// the fast unit/property suite so `make test` stays usable as a pre-commit gate.
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    }
}

val integrationTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val integrationTestRuntimeOnly: Configuration by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial"))
    options.errorprone {
        // Error Prone's own bug-pattern set only; we do not opt in to the experimental
        // checks, which produce enough noise to train people to ignore the build output.
        disableWarningsInGeneratedCode = true
        excludedPaths = ".*/build/generated/.*"
        disable("StringCaseLocaleUsage")
    }
}

tasks.named<JavaCompile>("compileJmhJava") { options.errorprone.enabled = false }
tasks.named<JavaCompile>("compileTestJava") { options.errorprone.enabled = false }
tasks.named<JavaCompile>("compileIntegrationTestJava") { options.errorprone.enabled = false }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
    // Long mode raises the repetition counts on the concurrency and model-based suites.
    val longMode = System.getProperty("willcall.longMode", "false")
    systemProperty("willcall.longMode", longMode)
    // jqwik reads its default try count from this property, so the model-based suite scales with
    // the same switch as everything else rather than needing its own.
    systemProperty("jqwik.tries.default", if (longMode == "true") "10000" else "1000")
    maxHeapSize = "3g"
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}

tasks.test {
    description = "Fast suite: unit tests, property tests, in-memory model tests."
}

val integrationTest by tasks.registering(Test::class) {
    description = "Testcontainers suite: real PostgreSQL and Redis."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    shouldRunAfter(tasks.test)
    // Testcontainers reuse keeps the 10k-concurrency suite from paying container start-up
    // on every class; see docs/testing.md.
    systemProperty("testcontainers.reuse.enable", "true")
}

tasks.check { dependsOn(integrationTest) }

jacoco { toolVersion = "0.8.13" }

tasks.jacocoTestReport {
    // Coverage is reported across both suites: the reservation core's hardest paths only run
    // under Testcontainers, and a figure that counted only the fast suite would understate them.
    executionData.setFrom(fileTree(layout.buildDirectory).include("jacoco/*.exec"))
    dependsOn(tasks.test, integrationTest)
    reports {
        xml.required = true
        html.required = true
    }
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    // Generated Spring bootstrap and DTO records carry no branches worth gating on.
                    exclude("**/WillcallApplication.class", "**/config/**")
                }
            },
        ),
    )
}

spotless {
    java {
        googleJavaFormat("1.28.0")
        target("src/*/java/**/*.java")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle { ktlint("1.5.0") }
}

jmh {
    warmupIterations = 3
    iterations = 5
    fork = 1
    resultFormat = "JSON"
    resultsFile = layout.buildDirectory.file("results/jmh/results.json")
}

springBoot {
    buildInfo()
}
