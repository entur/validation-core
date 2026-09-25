plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.protobuf:protobuf-java:4.36.2")
    implementation("org.yaml:snakeyaml:2.3")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.4.20")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("fixtureDir", layout.projectDirectory.dir("src/test/resources/fixture").asFile.absolutePath)
    // buildSrc is its own separate Gradle build - this reaches into the main build's http-model
    // module by relative path rather than any Gradle project reference.
    systemProperty("httpModelProtoDir", layout.projectDirectory.dir("../http-model/src/main/proto").asFile.canonicalPath)
}
