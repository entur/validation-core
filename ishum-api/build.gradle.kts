plugins {
    id("entur.grpc-openapi-module")
}

description = "Ishum's gRPC/REST service definitions (entur.ishum.v1) and their Request/Response messages, built on the shared validation-model domain messages."

grpcOpenApiModule {
    specFileName = "ishum.yaml"
}

dependencies {
    api(project(":validation-model"))
    api(libs.bundles.protobuf)
}
