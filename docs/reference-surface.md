# VectorCraft reference surface (revision 65c5953)

Reference: read-only checkout at `clones-ref/vectorcraft`. These facts come from source inspection, not from a running VectorCraft process.

| Surface | Reference pointer | Wave 1 choice |
|---|---|---|
| Engine registry | `crates/engine/src/cmd/mod.rs:88-117,173-225`: `CommandSpec` has id, label, menu, shortcut, params, enablement and run; modules compose `command_specs` | Extract `cmd!` literal id, label, params from every `crates/engine/src/cmd/*.rs` into `resources/hive_vectorcraft/catalog.edn`; 519 commands. `enabled` is session-dependent and **not** represented as static truth. |
| MCP | `crates/mcp/src/tools.rs:74-115`: `tool_definitions` builds tool descriptors and input schemas as Rust expressions | Extract 25 names/titles/descriptions and the *unevaluated* exact Rust `input-schema-source` expressions; these are **not** JSON Schema objects. Never promise validation against them until evaluated through the engine. |
| Backend | `crates/mcp/src/backend.rs:1-30,70-80`: headless or remote backend; remote sends one JSON line and reads a response | A `ControlTransport` port accepts request values; only injected test stub/recording decorator exists in wave 1. |
| Control | `docs/control-protocol.md:1-33`: loopback TCP, no auth, request `{id,method,params}`, response `{id,ok,result}` or `{id,ok:false,error}` | Extract method spellings from control method table (24 methods from the method rows (abbreviated `.confirm` is expanded to `ui.dialog.confirm`)). Core constructs request values and validates responses; no live TCP adapter yet. |

The reference control port is **unauthenticated**, loopback only, rejects malformed frames by closing the connection, caps a line at 4 MiB and serves at most 16 connections (`docs/control-protocol.md:3-13`). Do not expose it on an untrusted interface. This addon does not start or connect to the reference process.

The upstream checkout supplies both `LICENSE-MIT` and `LICENSE-APACHE`; this bridge is MIT. Extraction is reproducible with `clojure -M dev/extract_catalog.clj /absolute/path/to/vectorcraft` and should be repeated after upstream changes. No reference binary was built or executed here.
