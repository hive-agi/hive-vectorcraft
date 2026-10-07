# VectorCraft native seams (reference 65c5953)

Reference: `clones-ref/vectorcraft` at `65c5953`; paths below are relative to that checkout. This is a read-only source study, not a live integration test.

## Primary seam

1. **`vectorcraft_mcp::Headless` + `Backend::call`**: `Headless::new/with_document` owns an engine `Session`, CPU `Renderer`, and view; `Backend::call("engine.execute", {command,params})` executes commands; `app.open/save/export`, `engine.commands`, `document.inspect`, and `ui.render` are already available without a desktop (crates/mcp/src/headless.rs:9-16,88-125,228-300). Best candidate for a cdylib preserving the *control-method* vocabulary. Keep this instance alive between calls (crates/mcp/src/headless.rs:9-16; crates/render/src/lib.rs:274-302).
2. **`vectorcraft_engine::Session` directly**: `Session::new`, `execute(&mut self, id: &str, params: &Value) -> Result<Value>`, `commands`, `active`, `close_document` give the smallest core seam (crates/engine/src/lib.rs:471-488,516-578,579-616). The wrapper must implement host convenience commands and rendering itself; the headless adapter already does those (crates/mcp/src/headless.rs:88-125,228-300).
3. **`vectorcraft_mcp::Server` / desktop control**: MCP's `Backend` abstraction and its `Headless` implementation can be embedded, but the `Server` adds an unnecessary stdio JSON-RPC envelope; the GUI control handler needs `VectorcraftApp` and `egui::Context` (crates/mcp/src/lib.rs:1-30; crates/mcp/src/backend.rs:9-19; crates/ui-egui/src/control.rs:106-127). Reserve desktop control for UI-only operations rather than the in-process cut.

More evidence and limitations below.
