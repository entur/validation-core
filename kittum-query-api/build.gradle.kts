plugins {
    id("entur.grpc-openapi-module")
}

description = "Kittum's read-only query API (entur.kittum.query.v1) for runtime consumers such as misharum, built on the shared validation-model domain messages."

grpcOpenApiModule {
    specFileName = "kittum-query.yaml"
}

dependencies {
    api(project(":validation-model"))
    api(libs.bundles.protobuf)
}
