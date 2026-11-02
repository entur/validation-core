plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.protobuf:protobuf-java:4.36.2")
    implementation("org.yaml:snakeyaml:2.7")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.4.21")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("fixtureDir", layout.projectDirectory.dir("src/test/resources/fixture").asFile.absolutePath)
    // buildSrc is a separate build, so http-model is reached by path, not project reference.
    systemProperty("httpModelProtoDir", layout.projectDirectory.dir("../http-model/src/main/proto").asFile.canonicalPath)
}
