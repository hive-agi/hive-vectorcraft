//! Stable JSON C boundary over the reference's in-process Headless backend.
use std::ffi::{CStr, CString, c_char};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Mutex, OnceLock};

use serde_json::{Value, json};
use vectorcraft_mcp::{Backend, Headless};

static ENGINE: OnceLock<Mutex<Headless>> = OnceLock::new();

// Method names and aliases are transcribed from vectorcraft-mcp's Headless::call match.
// Engine commands remain data behind engine.commands / engine.execute.
const METHODS: &[(&str, &str, bool)] = &[
    ("engine.execute", "Execute an engine command with command and params", false),
    ("ui.menu.invoke", "Execute a menu command alias", false),
    ("command", "Execute a command alias", false),
    ("engine.commands", "List engine commands and host aliases", true),
    ("document.inspect", "Inspect the active document", true),
    ("document.node", "Inspect a document node", true),
    ("document.json", "Read the active document as JSON", true),
    ("ui.tool.select", "Select an active tool", false),
    ("ui.tool.list", "List available tool groups", true),
    ("ui.pointer", "Apply document-space pointer events", false),
    ("ui.key", "Dispatch a tool or command key", false),
    ("ui.render", "Render the active artboard as PNG", false),
    ("ui.screenshot", "Render the active artboard as PNG (alias)", false),
    ("app.open", "Open a document from a path", false),
    ("app.save", "Save the active document to a path", false),
    ("app.export", "Export the active document", false),
    ("app.quit", "Acknowledge quit (the process owns the session)", true),
];

fn error(message: impl AsRef<str>) -> Value {
    json!({"ok": false, "error": message.as_ref()})
}

fn dispatch(op: *const c_char, request: *const c_char) -> Value {
    if op.is_null() { return error("null op pointer: supply a UTF-8 method name"); }
    if request.is_null() { return error("null request pointer: supply a JSON object"); }
    // SAFETY: callers promise readable, NUL-terminated strings (standard C string contract).
    let op = match unsafe { CStr::from_ptr(op) }.to_str() {
        Ok(s) => s,
        Err(_) => return error("non-UTF-8 op: supply a UTF-8 method name"),
    };
    let request = match unsafe { CStr::from_ptr(request) }.to_str() {
        Ok(s) => s,
        Err(_) => return error("non-UTF-8 request: supply UTF-8 JSON"),
    };
    let params: Value = match serde_json::from_str(request) {
        Ok(Value::Object(map)) => Value::Object(map),
        Ok(_) => return error("request must be a JSON object"),
        Err(e) => return error(format!("malformed JSON request: {e}")),
    };
    let result = match op {
        "ops" => Ok(json!(METHODS.iter().map(|(name, doc, pure)|
            json!({"name": name, "doc": doc, "pure": pure})).collect::<Vec<_>>())),
        "capabilities" => Ok(json!({"library": "vectorcraft", "version": env!("CARGO_PKG_VERSION"),
            "reference": "65c5953516f1ba46ce6d3cd558dd759bd6fcf09f"})),
        name if METHODS.iter().any(|(method, _, _)| *method == name) => {
            match ENGINE.get_or_init(|| Mutex::new(Headless::new())).lock() {
                Ok(mut engine) => engine.call(name, params),
                Err(_) => Err("engine lock poisoned after panic; restart the process".into()),
            }
        }
        _ => Err(format!("unknown op `{op}`: use `ops` to list methods")),
    };
    match result {
        Ok(value) => json!({"ok": true, "value": value}),
        Err(message) => error(message),
    }
}

fn panic_message(panic: Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = panic.downcast_ref::<&str>() { (*s).to_owned() }
    else if let Some(s) = panic.downcast_ref::<String>() { s.clone() }
    else { "non-string panic payload".to_owned() }
}

/// Call one Headless method. The returned allocation belongs to the caller; release with hive_free.
/// `timeout_ms`, when supplied in the request, is advisory: the synchronous engine cannot be interrupted.
#[unsafe(no_mangle)]
pub extern "C" fn hive_call(op: *const c_char, request_json: *const c_char) -> *mut c_char {
    let envelope = catch_unwind(AssertUnwindSafe(|| dispatch(op, request_json)))
        .unwrap_or_else(|panic| error(format!("internal panic: {}", panic_message(panic))));
    let serialized = catch_unwind(AssertUnwindSafe(|| envelope.to_string()))
        .unwrap_or_else(|_| "{\"ok\":false,\"error\":\"internal panic: response serialization\"}".to_owned());
    match CString::new(serialized) {
        Ok(s) => s.into_raw(),
        Err(_) => CString::new("{\"ok\":false,\"error\":\"internal panic: NUL in response\"}")
            .expect("constant has no NUL").into_raw(),
    }
}

/// Release a pointer returned by hive_call. Null is accepted.
#[unsafe(no_mangle)]
pub extern "C" fn hive_free(p: *mut c_char) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if !p.is_null() {
            // SAFETY: only pointers returned by hive_call may be passed to hive_free.
            drop(unsafe { CString::from_raw(p) });
        }
    }));
}
