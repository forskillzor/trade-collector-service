plugins {
    kotlin("jvm") version "2.2.20"  // ✅ Исправить на 1.9.0
    kotlin("plugin.serialization") version "2.2.20"  // ✅ Та же версия
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.aandios"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
}
application {
    mainClass.set("com.aandios.MainKt")
}
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks {
    named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        archiveBaseName.set("trade-collector")
        archiveClassifier.set("")
        archiveVersion.set("")
        mergeServiceFiles()
        manifest {
            attributes("Main-Class" to "com.aandios.MainKt")
        }
    }

    build {
        dependsOn("shadowJar")
    }
}

dependencies {
    val ktor_version = "3.2.0"
    // Ktor Client (WebSocket)
    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("io.ktor:ktor-client-cio:$ktor_version")
    implementation("io.ktor:ktor-client-websockets:$ktor_version")

    // Ktor Server (Monitoring)
    implementation("io.ktor:ktor-server-core:$ktor_version")
    implementation("io.ktor:ktor-server-jetty:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")

    // JSON
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    //Database
    implementation("org.postgresql:postgresql:42.7.1")

    // HikariCP
    implementation("com.zaxxer:HikariCP:6.0.0")

    // t-Digest
    implementation("com.tdunning:t-digest:3.3")
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation(kotlin("test"))
}