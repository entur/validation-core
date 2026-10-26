plugins {
    id("entur.grpc-openapi-module")
}

description = "Zagar's gRPC/REST service definitions (entur.zagar.v1) and their Request/Response messages, built on the shared validation-model domain messages."

grpcOpenApiModule {
    specFileName = "zagar.yaml"
}

dependencies {
    api(project(":validation-model"))
    api(libs.bundles.protobuf)
}
