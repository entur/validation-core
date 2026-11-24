plugins {
    id("no.entur.validation.grpc-openapi")
}

description = "Kittum's read-only query API (entur.kittum.query.v1) for runtime consumers such as misharum, built on the shared validation-model domain messages."

grpcOpenApiModule {
    specFileName = "kittum-query.yaml"
    importProtoDirs.from(rootProject.file("http-model/src/main/proto"), rootProject.file("validation-model/src/main/proto"))
}

dependencies {
    api(project(":http-model"))
    api(project(":validation-model"))
    api(libs.bundles.protobuf)
}
