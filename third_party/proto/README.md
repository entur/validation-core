# Vendored third-party protos

The `.proto` files our own protos import, copied in so that neither `buf` nor the build fetches them
from the Buf Schema Registry (buf.build). Only the files actually imported, directly or
transitively, are here, formatted with `buf format` like the rest of the workspace but
otherwise unchanged. Do not edit them; replace them from upstream instead.

| Directory | Upstream | Version | License |
|---|---|---|---|
| `google/api` | `buf.build/googleapis/googleapis` ([googleapis/googleapis](https://github.com/googleapis/googleapis)) | BSR commit `c17df5b2beca46928cc87d5656bd5343` | Apache-2.0, `google/LICENSE` |
| `gnostic/openapi/v3` | `buf.build/gnostic/gnostic` ([google/gnostic](https://github.com/google/gnostic)) | BSR commit `087bc8072ce44e339f213209e4d57bf0` | Apache-2.0, `gnostic/LICENSE` |

`google/protobuf/*` (the well-known types) is not here: `buf` and `protoc` ship it themselves.

## Updating

To pull in a newer upstream version, or a file a new import needs, export it from a scratch buf
workspace that depends on the module and copy over the files:

```bash
buf export buf.build/googleapis/googleapis:<commit> -o /tmp/googleapis --path google/api/<file>.proto
```

Then run `buf format -w` and update the version column above. `http-model` ships this directory in
its jar, so consumers such as kittum get the same files.
