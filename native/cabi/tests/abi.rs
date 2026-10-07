use std::ffi::{CStr, CString, c_char};
use serde_json::{Value, json};
use vectorcraft::{hive_call, hive_free};

fn call(op: &str, params: Value) -> Value {
    let op = CString::new(op).unwrap();
    let params = CString::new(params.to_string()).unwrap();
    let ptr = hive_call(op.as_ptr(), params.as_ptr());
    assert!(!ptr.is_null());
    let result: Value = serde_json::from_str(unsafe { CStr::from_ptr(ptr) }.to_str().unwrap()).unwrap();
    hive_free(ptr);
    result
}

#[test]
fn catalogue_and_capabilities() {
    let rows = call("ops", json!({}))["value"].as_array().unwrap().clone();
    assert!(!rows.is_empty());
    for row in &rows {
        let name = row["name"].as_str().unwrap();
        assert!(!name.is_empty());
        assert!(!row["doc"].as_str().unwrap().is_empty());
        assert!(row["pure"].is_boolean());
        let response = call(name, json!({}));
        assert!(response["ok"].is_boolean(), "{name}: {response}");
        assert!(!response["error"].as_str().unwrap_or("").contains("unknown op"), "{name}: {response}");
    }
    let caps = call("capabilities", json!({}));
    assert_eq!(caps["value"]["library"], "vectorcraft");
    assert_eq!(caps["value"]["version"], env!("CARGO_PKG_VERSION"));
    assert_eq!(caps["value"]["reference"].as_str().unwrap().len(), 40);
}

#[test]
fn errors_are_envelopes() {
    assert!(call("missing", json!({}))["error"].as_str().unwrap().contains("unknown op"));
    let op = CString::new("ops").unwrap();
    for (operation, request) in [
        (std::ptr::null(), op.as_ptr()),
        (op.as_ptr(), std::ptr::null()),
    ] {
        let ptr = hive_call(operation, request);
        let value: Value = serde_json::from_str(unsafe { CStr::from_ptr(ptr) }.to_str().unwrap()).unwrap();
        assert_eq!(value["ok"], false);
        assert!(value["error"].as_str().unwrap().contains("null"));
        hive_free(ptr);
    }
    for text in ["{broken", "[]", "null"] {
        let request = CString::new(text).unwrap();
        let ptr = hive_call(op.as_ptr(), request.as_ptr());
        let value: Value = serde_json::from_str(unsafe { CStr::from_ptr(ptr) }.to_str().unwrap()).unwrap();
        assert_eq!(value["ok"], false);
        hive_free(ptr);
    }
    let bad = [0xffu8, 0];
    let ptr = hive_call(op.as_ptr(), bad.as_ptr().cast::<c_char>());
    let value: Value = serde_json::from_str(unsafe { CStr::from_ptr(ptr) }.to_str().unwrap()).unwrap();
    assert!(value["error"].as_str().unwrap().contains("UTF-8"));
    hive_free(ptr);
    hive_free(std::ptr::null_mut());
}

#[test]
fn document_flow() {
    let start = std::time::Instant::now();
    let execute = |command: &str, params: Value| {
        let r = call("engine.execute", json!({"command": command, "params": params}));
        assert_eq!(r["ok"], true, "{command}: {r}");
        r["value"].clone()
    };
    execute("file.new", json!({}));
    execute("shape.rectangle", json!({"x":10,"y":10,"width":80,"height":40}));
    execute("shape.ellipse", json!({"x":100,"y":100,"width":60,"height":60}));
    execute("document.inspect", json!({}));
    let rendered = call("ui.render", json!({"scale":0.25}));
    assert_eq!(rendered["ok"], true, "{rendered}");
    let v = &rendered["value"];
    let png = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, v["pngBase64"].as_str().unwrap()).unwrap();
    assert_eq!(&png[..8], b"\x89PNG\r\n\x1a\n");
    assert_eq!(u32::from_be_bytes(png[16..20].try_into().unwrap()), v["width"].as_u64().unwrap() as u32);
    assert_eq!(u32::from_be_bytes(png[20..24].try_into().unwrap()), v["height"].as_u64().unwrap() as u32);
    eprintln!("end-to-end flow: {:?}, {}x{}, {} PNG bytes", start.elapsed(), v["width"], v["height"], png.len());
}

#[test]
fn exported_symbols_load() {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../..");
    let _ = path; // cargo builds the cdylib in the configured target dir.
    let target = std::env::var("CARGO_TARGET_DIR").unwrap_or_else(|_| "target".into());
    let library = std::path::Path::new(&target).join("debug/libvectorcraft.so");
    let lib = unsafe { libloading::Library::new(&library) }.unwrap_or_else(|e| panic!("{}: {e}", library.display()));
    unsafe {
        let _: libloading::Symbol<unsafe extern "C" fn(*const c_char, *const c_char) -> *mut c_char> = lib.get(b"hive_call").unwrap();
        let _: libloading::Symbol<unsafe extern "C" fn(*mut c_char)> = lib.get(b"hive_free").unwrap();
    }
}
