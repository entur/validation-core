plugins {
    kotlin("jvm") version "2.4.10" apply false
}

allprojects {
    group = "no.entur.validation"

    repositories {
        mavenCentral()
    }
}

// Root-level: `buf format` rewrites the whole workspace in one shot.
val bufFormat = tasks.register<Exec>("bufFormat") {
    description = "Formats all .proto files in place via `buf format -w`."
    group = "build"
    inputs.files(fileTree(rootDir) { include("*/src/main/proto/**/*.proto") })
    inputs.files("buf.yaml", "buf.lock")
    commandLine(miseExecutable("buf"), "format", "-w")
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
            vendor = JvmVendorSpec.ADOPTIUM
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    tasks.matching { it.name == "bufGenerate" }.configureEach { dependsOn(bufFormat) }

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
        repositories {
            maven {
                name = "EnturJFrog"
                url = uri("https://entur2.jfrog.io/entur2/entur-release-standard/")
                credentials {
                    username = (findProperty("enturArtifactoryUser") as String?) ?: System.getenv("ARTIFACTORY_AUTH_USER")
                    password = (findProperty("enturArtifactoryPassword") as String?) ?: System.getenv("ARTIFACTORY_AUTH_TOKEN")
                }
            }
        }
    }
}
