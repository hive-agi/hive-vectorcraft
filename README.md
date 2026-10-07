# hive-vectorcraft

VectorCraft vector editing vocabulary as a host-neutral `IAddon` (`hive.vectorcraft`). Wave 1 ships an extracted command catalog, a portable request and response core, and one MCP tool. **No live editing transport ships yet.** Calls without an injected transport refuse rather than simulate an edit.

The reference [VectorCraft](https://github.com/vectorcraft/vectorcraft) is a Rust Illustrator clean-room implementation (revision 65c5953). Its checkout has dual MIT/Apache-2.0 license files. This addon is MIT. [Measured surface and source pointers](docs/reference-surface.md).

## One vocabulary, planned transports

| Transport | Wave 1 | Planned behavior |
|---|---|---|
| `ControlTransport` recording stub | yes, test only | injected response and captured request values |
| cljw control channel | wave 2 | loopback TCP JSON lines to the reference desktop app; no authentication in upstream protocol |
| cljrs / Rust cdylib | wave 2 | load reference engine crates in-process through a Rust cdylib, never invoke their CLI as a library surrogate |
| cljs | portable core only | Node oracle, no live adapter |

The VectorCraft loopback control port must not be exposed to untrusted networks: upstream does not authenticate. See `docs/control-protocol.md` in the reference checkout.

## MCP surface

One `vectorcraft` tool with `command` = `catalog`, `doctor`, or `call`:

- `catalog`: no query returns counts and revision; query `engine`, `mcp`, `control` lists that section; any exact engine id returns its descriptor. Extracted data: **519** engine commands, **25** MCP tools and **24** control methods. MCP input schemas are retained as source expressions, not falsely advertised as resolved JSON Schema.
- `doctor`: catalog revision, injected vs absent transport, actionable hint.
- `call`: `engine_command`, `params`, `id`; checks exact catalog id and map params before dispatch. With no port, returns `:vectorcraft/no-transport` and the fix.

Example JVM usage with an injected stub:

```clojure
(require '[hive-vectorcraft.addon :as vc]
         '[hive-vectorcraft.stub :as stub]
         '[hive-addon.protocol :as addon])
(let [instance (vc/addon-ctor {:transport (stub/stub {:ok true :result {:id 1}})})]
  (addon/initialize! instance {})
  ((:handler (first (addon/tools instance)))
   {"command" "call" "engine_command" "shape.rectangle" "params" {:x 0 :y 0 :width 10 :height 10}}))
```

`hive-vectorcraft.stub` is test-only; use the `:dev` or `:test` alias to run that example.

## Layout

| File | Portable core | JVM | Native | cljs |
|---|---:|---:|---:|---:|
| `src/hive_vectorcraft/core.cljc` request, catalog lookup, frame/response values | yes | yes | source loads | yes |
| `src/hive_vectorcraft/{catalog,port,service,addon}.clj` IAddon and injected boundary | no | yes | not yet | no |
| `resources/hive_vectorcraft/catalog.edn` pinned reference data | data | yes | load as data | data |
| `dev/extract_catalog.clj` reproducible registry extractor | no | dev | no | no |
| `dev/portability.cljc` 80-case oracle | yes | yes | yes | yes |
| `test/hive_vectorcraft/{stub,core_test,service_test}.clj` injected tests | no | yes | no | no |
| `native/` | no | no | wave 2 | no |

## Verify

Run the focused suite on the cider-spawned `:dev` nREPL with `clojure.test/run-tests`; final cold suite: `clojure -J-Xmx2g -M:test`. Run `bash dev/verify_portability.sh jvm cljw cljrs cljs` with `VECTORCRAFT_REPL_PORT` set to the cider port. The cljs leg compiles using hive-cljs's shadow-cljs toolchain and executes Node. To regenerate the catalog from a read-only upstream checkout: `clojure -M dev/extract_catalog.clj /path/to/vectorcraft`.

## Measured here / not verified here

Measured on reference source: control requests are newline-separated JSON, no auth, loopback only; 4 MiB line limit and 16-connection limit; command metadata is session-dependent for enablement. Local: 519 registry commands / 25 MCP tools / 37 method tokens extracted. Verified: JVM, cljw, cljrs and cljs oracle. **Not verified here:** a running VectorCraft GUI, TCP connection, live exports or render, Rust cdylib, evaluated MCP JSON schemas, command-parameter semantics inside the upstream engine. Wave 1 never launches the reference app.
