plugins {
    id("entur.grpc-openapi-module")
}

description = "Kittum's gRPC/REST service definitions (entur.kittum.v1) and their Request/Response messages, built on the shared validation-model domain messages."

grpcOpenApiModule {
    specFileName = "kittum.yaml"
}

dependencies {
    api(project(":validation-model"))
    api(libs.bundles.protobuf)
}
