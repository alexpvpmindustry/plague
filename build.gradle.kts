plugins {
    kotlin("jvm") version "1.9.22"
}

group = "com.github.kennarddh.mindustry"
version = "0.0.20"

repositories {
    mavenCentral()
    maven("https://maven.xpdustry.com/releases")
    maven("https://maven.xpdustry.com/mindustry")
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")

    project.group = "com.github.kennarddh.mindustry"

    repositories {
        mavenCentral()
        maven("https://maven.xpdustry.com/releases")
        maven("https://maven.xpdustry.com/mindustry")
    }

    java {
        withSourcesJar()
        withJavadocJar()
    }

    sourceSets {
        main {
            java.srcDir("src/main/kotlin")
        }
    }
}