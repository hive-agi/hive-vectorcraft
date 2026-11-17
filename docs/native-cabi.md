# VectorCraft native C ABI

The Rust `vectorcraft-cabi` crate wraps the reference's in-process `vectorcraft_mcp::Headless` (`Backend::call`) at reference revision `0f8040ef59b88d33dd97563dbb87e8efedf046ff`. It is a GUI-free library, not a process or socket adapter. Version 0.4.0; MIT.

```c
char *hive_call(const char *op, const char *request_json);
void hive_free(char *response);
```

Pass an object as UTF-8 JSON. The caller owns the returned string and must release it with `hive_free` (passing null to `hive_free` is safe). A response is either `{"ok":true,"value":...}` or `{"ok":false,"error":"..."}`. Null pointers, malformed JSON, non-UTF-8 strings and unknown operations produce error envelopes. A Rust unwind is caught at both exported boundaries; a caught call panic yields `internal panic: ...`. Arbitrarily invalid non-null C pointers cannot be validated by a C ABI. Upstream `Headless` is not `Send` (its renderer holds `Rc` caches), so the library runs it on one dedicated owner thread (`vectorcraft-engine`), spawned lazily on the first engine call; that thread constructs and owns the single process-wide session. `hive_call` may be called from any thread (JVM FFM, cljrs, cljw): it sends the request over a channel and blocks on the reply, so every caller sees the same engine and calls are serialized. A panic *inside* the engine is caught on the owner thread and answered as `internal panic: ...`; the thread stays alive but the session is poisoned and later engine calls return an actionable restart error. If the owner thread itself is gone, engine calls return an error envelope instead of blocking. `ops` and `capabilities` never touch the owner thread.

## Measured operation catalog

These 17 Headless methods are exposed as operations; `ops` and `capabilities` are two additional metadata operations (19 total). Engine commands remain discoverable **data** behind `engine.commands` and `engine.execute`, not one C operation per command. `pure` means the method neither changes document/session state nor touches the filesystem; rendering is conservatively impure because it accepts a `path`.

| Op | Doc (`ops` description) | Pure |
| --- | --- | --- |
| `engine.execute` | Execute an engine command with command and params | false |
| `ui.menu.invoke` | Execute a menu command alias | false |
| `command` | Execute a command alias | false |
| `engine.commands` | List engine commands and host aliases | true |
| `document.inspect` | Inspect the active document | true |
| `document.node` | Inspect a document node | true |
| `document.json` | Read the active document as JSON | true |
| `ui.tool.select` | Select an active tool | false |
| `ui.tool.list` | List available tool groups | true |
| `ui.pointer` | Apply document-space pointer events | false |
| `ui.key` | Dispatch a tool or command key | false |
| `ui.render` | Render the active artboard as PNG | false |
| `ui.screenshot` | Render the active artboard as PNG (alias) | false |
| `app.open` | Open a document from a path | false |
| `app.save` | Save the active document to a path | false |
| `app.export` | Export the active document | false |
| `app.quit` | Acknowledge quit (the process owns the session) | true |

`capabilities` reports `{library:"vectorcraft",version:"0.4.0",reference:"0f8040ef59b88d33dd97563dbb87e8efedf046ff"}`. `app.quit` acknowledges (value `null`) but does not tear down the process-wide session or its owner thread. `timeout_ms` is **advisory**: the synchronous engine cannot be interrupted by this boundary. Callers must arrange external time limits if needed. File-bearing commands can read or write paths provided by callers; use a restricted host process for untrusted requests.

## Build and test

```sh
cd /home/klein/PP/hive/hive-vectorcraft-wt/cabi/native/cabi
export CARGO_BUILD_JOBS=4 CARGO_TARGET_DIR=/home/klein/.cache/hive-craft-target/vectorcraft
cargo build
cargo test -- --test-threads=1
```

The build must precede the tests: Cargo's `cargo test` alone need not place the standalone cdylib in `debug/`, which the `libloading` smoke opens by filename. Measured on this workstation: four ABI integration tests pass (plus zero unit and doc tests). The C-level smoke resolves both exported symbols from `/home/klein/.cache/hive-craft-target/vectorcraft/debug/libvectorcraft.so`; debug artifact: **593,380,888 bytes** (size is toolchain/profile-dependent). The document flow creates a document, executes rectangle, ellipse and inspect commands, saves/reopens, renders to PNG and verifies the PNG signature and dimensions: **14.67 ms**, 153 × 198 pixels, 1,890 PNG bytes in one warm test run. This timing is not a throughput guarantee. No deliberate engine panic is available as a safe test input; error envelopes and non-UTF-8/null arguments are tested instead. The C ABI is exercised directly through `CString`/`CStr` and `hive_free` in integration tests.
