description = "Kafka tracing (X-Correlation-Id/ET-Client-Name headers and the matching MDC entries) " +
    "and dead-lettering (failure classification, the DeadLetter envelope recoverer and error " +
    "handler) shared by validation-platform services."

plugins {
    alias(libs.plugins.ktlint)
}

repositories {
    exclusiveContent {
        forRepository { maven("https://packages.confluent.io/maven/") }
        filter { includeGroup("io.confluent") }
    }
}

dependencies {
    // Scope application BOMs to application/test classpaths, not tool configurations like ktlint -
    // same reasoning as proto-utils.
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.kotlin.bom))

    // DeadLetter, RecordInterceptor, KafkaOperations/ConsumerRecordRecoverer/DefaultErrorHandler,
    // Kafka's Headers/ProducerInterceptor and Logger appear in this module's public signatures.
    api(project(":validation-model"))
    api("org.springframework.kafka:spring-kafka")
    api("org.slf4j:slf4j-api")

    // Only for FailureClassifier's RestClientException check; every consumer already has it at
    // runtime through its Confluent serdes, so it is not forced on them in a version of ours.
    // Its Confluent-built kafka-clients (`-ccs`) is not on Maven Central, and the Boot BOM's is the
    // one to compile and test against anyway.
    compileOnly(libs.kafka.schema.registry.client) { exclude(group = "org.apache.kafka") }

    testImplementation(libs.kafka.schema.registry.client) { exclude(group = "org.apache.kafka") }
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.mockito:mockito-core")
    // A real MDC adapter: without one slf4j falls back to a no-op MDC and the tests see nothing.
    testImplementation("ch.qos.logback:logback-classic")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The Boot BOM above is `implementation`, so without this the published api variant lists
// spring-kafka/slf4j-api versionless, and a Gradle consumer without that BOM can't resolve them.
publishing {
    publications.named<MavenPublication>("maven") {
        versionMapping { allVariants { fromResolutionResult() } }
    }
}

ktlint {
    version.set(libs.versions.ktlint)
}
