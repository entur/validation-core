# validation-core

Shared protobuf schema for Entur's validation platform, published as Java/Kotlin libraries. Two
Gradle modules - `validation-model` (the `entur.validation.v1` domain messages) and `kittum-api`
(Kittum's `entur.kittum.v1` service definitions) - each published as its own Maven artifact.

This repository contains schema and generated code only. There is no application implementation, no
database, no Dockerfile, and nothing deployable. The parts of the golden path that assume a running
service - Helm, Terraform, `compose.yaml`, `.entur/` self-service manifests, `application.yml` - do
not apply here.

## Entur Standards

Read and follow the Entur platform standards at:
https://github.com/entur/ai/blob/main/AGENTS.md

## Project-Specific

- Owning team: `team-validering`
- Artifacts: `no.entur.validation:validation-model`, `no.entur.validation:kittum-api`
- Published to Entur's JFrog Artifactory (`entur-release-standard`) via `entur/gha-artifactory`,
  which patch-bumps `gradle.properties` on every push to `main`
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
- **No tests.** Both modules report `test NO-SOURCE`; there is no hand-written code to exercise.
  This is a known gap rather than a decision - a smoke test proving the generated classes load and
  round-trip would be cheap insurance that codegen actually produces working output.

## Critical Rules

- PR titles ALWAYS carry the Jira ticket key: `feat(ETU-####): <description>` (preferred) or
  `ETU-####: <Description>`. No ticket in the branch name means asking the user for one - never
  invent a key.
- Never hand-edit `specs/*.yaml` or anything under `<module>/build/`. Change the `.proto` file and
  rebuild.
- `.proto` changes are wire-compatibility changes. Run `buf breaking` against `main` before
  proposing one; never renumber or remove an existing field.
