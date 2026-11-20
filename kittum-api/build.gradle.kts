plugins {
    id("no.entur.validation.grpc-openapi")
}

description = "Kittum's gRPC/REST service definitions (entur.kittum.v1) and their Request/Response messages, built on the shared validation-model domain messages."

grpcOpenApiModule {
    specFileName = "kittum.yaml"
    importProtoDirs.from(rootProject.file("http-model/src/main/proto"), rootProject.file("validation-model/src/main/proto"))
}

dependencies {
    api(project(":http-model"))
    api(project(":validation-model"))
    api(libs.bundles.protobuf)
}
