# Contributing

## Schema-first workflow

`.proto` files under `<module>/src/main/proto` are the source of truth. Generated Java/Kotlin
classes are build output.

1. Edit the `.proto` file.
2. `buf lint` - the rules, and this repository's deliberate exceptions to them, are in
   [`buf.yaml`](buf.yaml).
3. `buf breaking --against 'https://github.com/entur/validation-core.git#branch=main'` - these are
   published libraries, so a field renumbered or removed breaks every consumer. CI runs this on
   every PR.
4. `./gradlew build` - runs `buf generate` before compiling, and inspect
   `<module>/build/generated/source/proto` if you need to see exactly what was produced.

## Wire compatibility

Adding a field or an enum value is safe. Renumbering, removing, or changing the type of an existing
one is not. Reserve the tag and the name instead:

```protobuf
reserved 4;
reserved "old_field_name";
```

## Tooling

`buf` and `protoc` are pinned in [`.mise.toml`](.mise.toml); run `mise install` to match the pinned
versions. The Gradle build invokes `buf` through the mise shim by absolute path.

## Pull requests

PR titles carry the Jira ticket key: `feat(ETU-####): <description>` (preferred) or
`ETU-####: <Description>`.
