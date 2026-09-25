pluginManagement {
    plugins {
        kotlin("jvm") version "2.4.20"
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "validation-core"

include("http-model", "validation-model", "kittum-api")
