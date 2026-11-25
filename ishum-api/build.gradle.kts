plugins {
    id("no.entur.validation.grpc-openapi")
}

description = "Ishum's gRPC/REST service definitions (entur.ishum.v1) and their Request/Response messages, built on the shared validation-model domain messages."

grpcOpenApiModule {
    specFileName = "ishum.yaml"
    importProtoDirs.from(
        rootProject.file("http-model/src/main/proto"),
        rootProject.file("validation-model/src/main/proto"),
        rootProject.file("third_party/proto"),
    )
}

dependencies {
    api(project(":http-model"))
    api(project(":validation-model"))
    api(libs.bundles.protobuf)
}
