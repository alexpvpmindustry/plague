import fr.xpdustry.toxopid.dsl.mindustryDependencies
import fr.xpdustry.toxopid.spec.ModMetadata
import fr.xpdustry.toxopid.spec.ModPlatform
import fr.xpdustry.toxopid.task.GithubArtifactDownload
import fr.xpdustry.toxopid.task.MindustryExec

plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("fr.xpdustry.toxopid") version "3.2.0"
}

toxopid {
    compileVersion.set("v159.3")
    runtimeVersion.set("v159.3")
    platforms.add(ModPlatform.HEADLESS)
}

val metadata = ModMetadata.fromJson(project.file("plugin.json"))

project.version = metadata.version

val genesisVersion = "3.0.0-beta.27"

dependencies {
    mindustryDependencies()

    compileOnly("com.xpdustry:kotlin-runtime:3.1.1-k.1.9.22")
    compileOnly("com.github.kennarddh.mindustry:genesis-core:$genesisVersion")
    compileOnly("com.github.kennarddh.mindustry:genesis-standard:$genesisVersion")

    implementation("org.slf4j:slf4j-api:2.0.11")
}

configurations.runtimeClasspath {
    exclude("org.jetbrains.kotlin", "kotlin-stdlib")
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-common")
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk8")
    exclude("org.jetbrains.kotlin", "kotlin-reflect")
    exclude("org.jetbrains.kotlinx", "kotlinx-coroutines-core")
    exclude("org.jetbrains.kotlinx", "kotlinx-coroutines-jdk8")
    exclude("org.slf4j")
}

kotlin {
    jvmToolchain(17)
}

tasks {
    jar {
        doFirst {
            val metadataFile = temporaryDir.resolve("plugin.json")

            metadataFile.writeText(metadata.toJson(true))

            from(metadataFile)
        }

        manifest {
            attributes(mapOf("Multi-Release" to "true"))
        }

        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        from(sourceSets.main.get().output)
        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    }
}

val downloadKotlinRuntime =
    tasks.register<GithubArtifactDownload>("downloadKotlinRuntime") {
        user.set("xpdustry")
        repo.set("kotlin-runtime")
        name.set("kotlin-runtime.jar")
        version.set("v3.1.1-k.1.9.22")
    }

val downloadGenesisCore =
    tasks.register<GithubArtifactDownload>("downloadGenesisCore") {
        user.set("kennarddh-mindustry")
        repo.set("genesis")
        name.set("genesis-core-$genesisVersion.jar")
        version.set("v$genesisVersion")
    }

val downloadGenesisStandard =
    tasks.register<GithubArtifactDownload>("downloadGenesisStandard") {
        user.set("kennarddh-mindustry")
        repo.set("genesis")
        name.set("genesis-standard-$genesisVersion.jar")
        version.set("v$genesisVersion")
    }

tasks.runMindustryServer {
    mods.setFrom(
        setOf(
            tasks.jar,
            downloadKotlinRuntime,
            downloadGenesisCore,
            downloadGenesisStandard
        )
    )
}

tasks.runMindustryClient {
    mods.setFrom()
}

tasks.register<MindustryExec>("runMindustryClient2") {
    group = fr.xpdustry.toxopid.Toxopid.TASK_GROUP_NAME
    classpath(tasks.downloadMindustryClient)
    mainClass.convention("mindustry.desktop.DesktopLauncher")
    modsPath.convention("./mods")
    standardInput = System.`in`

    mods.setFrom()
}

publishing {
    repositories {
        maven {
            name = "reposilite"
            url = uri("http://23.95.107.12:9999/releases")
            credentials(PasswordCredentials::class)
            authentication {
                create<BasicAuthentication>("basic")
            }
            isAllowInsecureProtocol = true
        }
    }
    publications {
        create<MavenPublication>("maven") {
            groupId = groupId
            artifactId = metadata.name
            version = version
            from(components["java"])
        }
    }
}

tasks.register("getArtifactPath") {
    doLast { println(tasks.jar.get().archiveFile.get().toString()) }
}