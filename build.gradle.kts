plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    `maven-publish`
}

group = "tech.origin"
version = "0.1.0"
description = "A Kotlin backend framework for Ktor: coroutine-friendly Exposed transaction layer over Hikari, dual-token JWT authentication, unified error handling and response envelope, declarative configuration, and request validation."

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    api("io.ktor:ktor-server-core:3.3.2")
    api("io.ktor:ktor-server-auth:3.3.2")
    api("io.ktor:ktor-server-auth-jwt:3.3.2")
    api("io.ktor:ktor-server-sessions:3.3.2")
    api("io.ktor:ktor-server-status-pages:3.3.2")
    api("io.ktor:ktor-server-request-validation:3.3.2")
    api("io.ktor:ktor-server-content-negotiation:3.3.2")
    api("io.ktor:ktor-server-cors:3.3.2")
    api("io.ktor:ktor-server-compression:3.3.2")
    api("io.ktor:ktor-server-default-headers:3.3.2")
    api("io.ktor:ktor-server-call-logging:3.3.2")
    api("io.ktor:ktor-server-call-id:3.3.2")
    api("io.ktor:ktor-serialization-kotlinx-json:3.3.2")

    api("org.jetbrains.exposed:exposed-core:0.61.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.61.0")

    api("org.slf4j:slf4j-api:2.0.5")
    api("redis.clients:jedis:5.1.0")
    implementation("com.zaxxer:HikariCP:7.0.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjvm-default=all-compatibility")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["kotlin"])
        }
    }
}
