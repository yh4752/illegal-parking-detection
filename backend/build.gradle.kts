plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.spring") version "2.1.21"
    id("org.springframework.boot") version "3.5.16"
}

group = "kr.ac.sejong"
version = "0.1.0-SNAPSHOT"

repositories { mavenCentral() }

kotlin { jvmToolchain(17) }
springBoot { mainClass.set("kr.ac.sejong.parking.ParkingApplicationKt") }

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.integration:spring-integration-mqtt")
    implementation("org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("io.github.erdtman:java-json-canonicalization:1.1")
    implementation("com.networknt:json-schema-validator:1.5.9") {
        // The wire contract and schema are JSON only.
        exclude(group = "com.fasterxml.jackson.dataformat", module = "jackson-dataformat-yaml")
    }

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") }
}

// Package the authoritative contract directly; do not maintain a second schema copy.
val contractDirectory = layout.projectDirectory.dir("../reference/v1.43/개발자료/09_구현설계/contracts")
tasks.processResources {
    from(layout.projectDirectory.dir("../infra/sql")) {
        include("register-local-device.sql", "register-local-policy.sql")
        into("demo")
    }
    from(contractDirectory) {
        include("parking.schema.json")
        into("contracts")
    }
    from(layout.projectDirectory.dir("../contracts/effective")) {
        include("parking.schema.json", "openapi.json")
        into("contracts/effective")
    }
}
tasks.processTestResources {
    from(contractDirectory) {
        include("examples.json")
        into("contracts")
    }
    from(layout.projectDirectory.dir("../docs/design")) {
        include("event-validation-cases.json")
        into("design")
    }
}

tasks.test {
    useJUnitPlatform { excludeTags("database") }
}

tasks.register<Test>("databaseTest") {
    description = "Tests the v1.43 migration and constraints against a running PostgreSQL."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("database") }
    systemProperty("parking.test.runtimeClasspath", sourceSets["test"].runtimeClasspath.asPath)
    shouldRunAfter(tasks.test)
    // The database is external state; never reuse a previous successful test result.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

tasks.register<JavaExec>("initializeDemoZone") {
    description = "Inspect a fresh single-zone demo database; --apply explicitly opens its recovery hold. No HTTP/MQTT."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("kr.ac.sejong.parkingtools.DemoInitializationCliKt")
}

tasks.register<JavaExec>("manageWebAccount") {
    description = "Explicit local operator account/grant management. Password from console or owner-only secret file."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("kr.ac.sejong.parkingtools.WebAccountCliKt")
    standardInput = System.`in`
}
