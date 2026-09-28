# validation-core

Shared protobuf schema for Entur's validation platform, published as Java/Kotlin libraries. Five
Gradle modules - `http-model` (the shared `entur.http.v1` `Failure`/`ProblemDetail` types),
`validation-model` (the `entur.validation.v1` domain messages), `kittum-api` (Kittum's
`entur.kittum.v1` service definitions), `proto-utils` (hand-written, domain-agnostic proto/HTTP
plumbing built on the other three), and `exposed-utils` (JetBrains Exposed-specific persistence
support - ETag concurrency, cursor paging - built on `proto-utils`) - see "Non-schema modules" below
- each published as its own Maven artifact.

This repository is schema and generated code plus, in `proto-utils` and `exposed-utils`, hand-written
support code built on top of it - see "Non-schema modules" in `README.md`. There is no application
implementation, no database, no Dockerfile, and nothing deployable (`exposed-utils`'s Postgres-backed
tests run against a throwaway Testcontainers instance, not a real database). The parts of the golden
path that assume a running service - Helm, Terraform, `compose.yaml`, `.entur/` self-service
manifests, `application.yml` - do not apply here.

## Entur Standards

Read and follow the Entur platform standards at:
https://github.com/entur/ai/blob/main/AGENTS.md

## Project-Specific

- Owning team: `team-validering`
- Artifacts: `no.entur.validation:http-model`, `no.entur.validation:validation-model`,
  `no.entur.validation:kittum-api`, `no.entur.validation:proto-utils`,
  `no.entur.validation:exposed-utils`
- Published to Entur's JFrog Artifactory (`entur-release-standard`) via `entur/gha-artifactory`.
  Publishing is manual and tag-triggered: pushing a `v<major>.<minor>.<patch>` tag runs CD, which
  publishes that exact version. Merging to `main` does not publish by itself. See "Versioning and
  publishing" in `README.md`.
- Source of truth: `.proto` files under `<module>/src/main/proto`. Generated Java/Kotlin classes are
  build output and are never committed.
- `buf` and `protoc` are pinned in `.mise.toml`. Gradle's `Exec` resolves an executable against the
  Gradle JVM's own `PATH`, not the environment it hands the child process, so `bufGenerate` names
  the mise shim by absolute path. A bare `buf` works in CI but fails in a mise-based local setup.
- The consuming service lives in `entur/kittum`, its infrastructure in `entur/kittum-infrastructure`.

## Deviations from the golden path

- **`specs/` holds generated specs, not hand-written ones.** `CONVENTIONS.md` describes `specs/` as
  the home of contract-first OpenAPI specs. Here the `.proto` files are the contract and
  `specs/*.yaml` is generated from them by `buf generate`, then copied into the source tree so a
  schema change produces a reviewable diff of the resulting HTTP contract. The location matches the
  standard; the direction of authorship is inverted.
- **No tests in the schema modules.** `http-model`, `validation-model` and `kittum-api` all report
  `test NO-SOURCE`; there is no hand-written code to exercise. This is a known gap rather than a
  decision - a smoke test proving the generated classes load and round-trip would be cheap insurance
  that codegen actually produces working output. (`buildSrc`'s own build logic, e.g.
  `OpenApiFailureAugmenter`, does have unit tests - run separately via `./gradlew :buildSrc:test`,
  since buildSrc is compiled and jar'd automatically before every build but not tested as a side
  effect of it.) `proto-utils` and `exposed-utils` are the exception: they're hand-written, so
  they're fully tested - `exposed-utils`'s tests include Postgres-backed integration tests via
  Testcontainers.
- **`proto-utils`/`exposed-utils` are not API modules, but both use `buf` for codegen like the
  others.** Neither has a `src/main/proto`, and neither needs `copyOpenApiSpec`/the
  `entur.grpc-openapi-module` convention plugin the other API modules use. Each has its own
  test-fixtures `.proto` (`<module>/src/test/proto/no/entur/{proto,exposed}/testfixtures/v1/test.proto`)
  that goes through `bufGenerate` - `buf` + `protoc_builtin`, the same mechanism every other module
  uses, extracted into the `entur.test-proto-module` convention plugin
  (`buildSrc/src/main/kotlin/entur.test-proto-module.gradle.kts`) and applied independently by both
  modules. The two fixture files overlap on a handful of message shapes their tests both happen to
  need (`Widget` and friends, `Note`) - deliberately duplicated rather than shared across the module
  boundary via a `testFixtures` artifact, since each file is small and self-contained and the overlap
  has no compatibility guarantee to protect either way. Both are listed under root `buf.yaml`'s
  `modules:` (buf's PACKAGE_DIRECTORY_MATCH rule and its `google.api.field_behavior` dependency
  resolution both need workspace membership to work at all), each with the same pared-down
  per-module override - `lint.use: [STANDARD]` only (no COMMENTS, no UNARY_RPC) and
  `breaking.use: []` - since these are internal fixtures with no compatibility guarantee, not a
  published contract. Don't widen that override without a reason; don't add `except` entries to
  work around a real lint failure - fix the `.proto` instead (see the `ENUM_VALUE_PREFIX` fix in its
  git history for why: excepting a rule some existing code violates just spreads that violation
  further instead of shrinking it).
  See "Non-schema modules" in `README.md`.

## Critical Rules

- PR titles ALWAYS carry the Jira ticket key: `feat(ETU-####): <description>` (preferred) or
  `ETU-####: <Description>`. No ticket in the branch name means asking the user for one - never
  invent a key.
- Never hand-edit `specs/*.yaml` or anything under `<module>/build/`. Change the `.proto` file and
  rebuild.
- `.proto` changes are wire-compatibility changes. Run `buf breaking` against `main` before
  proposing one; never renumber or remove an existing field.
- `.proto` formatting is enforced by `./gradlew build` itself (the root `bufFormat` task runs
  `buf format -w` before codegen), not by a separate lint step - it rewrites files rather than
  failing. CI still fails if the rewrite produces a diff, meaning an unformatted file was committed.
- A field's own comment only reaches `specs/*.yaml` when that field becomes a path/query
  parameter. For a field bound via `body: "..."` in a `google.api.http` option, gnostic's
  protoc-gen-openapi emits a bare `$ref` to that field's message type and drops the wrapping
  field's comment entirely - so a `CreateXRequest`/`UpdateXRequest`'s embedded-resource field
  comment is invisible in the generated spec, no matter how detailed. Put real documentation -
  ignored/OUTPUT_ONLY fields, side effects, error conditions - on the rpc's own doc comment
  (which does become the operation's `description`), and keep body-bound field comments minimal.
