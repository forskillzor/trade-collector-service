plugins {
    kotlin("jvm") version "2.2.20"  // ✅ Исправить на 1.9.0
    kotlin("plugin.serialization") version "2.2.20"  // ✅ Та же версия
    application
    // Добавьте для native сборки:
    id("org.graalvm.buildtools.native") version "0.9.28"}

group = "com.aandios"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
}
application {
    mainClass.set("com.aandios.MainKt")
    applicationDefaultJvmArgs = listOf(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
    )
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

    // Database
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("org.jetbrains.exposed:exposed-core:0.44.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.44.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.44.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.44.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.7.3")

    // HikariCP
    implementation("com.zaxxer:HikariCP:6.0.0")

    //Postgres
    implementation("org.postgresql:postgresql:42.7.1")

    // Apache Arrow для хранения агрегированных данных
    implementation("org.apache.arrow:arrow-vector:14.0.1")
    implementation("org.apache.arrow:arrow-memory-netty:14.0.1")

    // Utils
    implementation("com.github.doyaaaaaken:kotlin-csv-jvm:1.9.2")}


tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(24)
}