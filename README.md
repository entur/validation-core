# validation-core

## Description

This repository contains shared code for Entur's validation platform, published as a Java library.
It contains schema and generated code only.

## Modules

One Gradle module, published as its own Maven artifact:

| Module | Proto package | Artifact | Contents |
|--------|---------------|----------|----------|
| [`validation-model`](validation-model) | `entur.validation.v1` | `no.entur.validation:validation-model` | The domain messages. |

More packages (and modules) are expected as new APIs are added on top of the shared model.

## Obtain

```shell
git clone git@github.com:entur/validation-core.git
```

## Build

```shell
./gradlew clean build
```

This runs `buf generate` on all modules before compiling. Each module's jar also bundles its own `.proto` sources,
so a consumer that needs the raw schema  can get it from the same published artifact.

### Prerequisites

* Java 25
* Gradle (wrapper included)
* [buf](https://buf.build/) and `protoc` - pinned in [`.mise.toml`](.mise.toml); install both
  directly or use [mise](https://mise.jdx.dev/) to match the pinned versions automatically

### Schema changes

`.proto` files under `<module>/src/main/proto` are the source of truth. After editing one:

```shell
buf lint
buf breaking --against 'https://github.com/entur/validation-core.git#branch=main'
./gradlew build
```

## Versioning and publishing

On every push to `main`, CD ([`.github/workflows/cd.yml`](.github/workflows/cd.yml)) bumps the
patch version in [`gradle.properties`](gradle.properties) and publishes all modules to Entur's
JFrog Artifactory (`entur-release-standard`) via
[`entur/gha-artifactory`](https://github.com/entur/gha-artifactory).