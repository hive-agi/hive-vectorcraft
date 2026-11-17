use serde_json::{Value, json};
use std::ffi::{CStr, CString, c_char};
use vectorcraft::{hive_call, hive_free};

fn call(op: &str, params: Value) -> Value {
    let op = CString::new(op).unwrap();
    let params = CString::new(params.to_string()).unwrap();
    let ptr = hive_call(op.as_ptr(), params.as_ptr());
    assert!(!ptr.is_null());
    let result: Value =
        serde_json::from_str(unsafe { CStr::from_ptr(ptr) }.to_str().unwrap()).unwrap();
    hive_free(ptr);
    result
}

#[test]
fn catalogue_and_capabilities() {
    let _held = DOCUMENT.lock().unwrap_or_else(|e| e.into_inner());
    let rows = call("ops", json!({}))["value"].as_array().unwrap().clone();
    assert!(!rows.is_empty());
    for row in &rows {
        let name = row["name"].as_str().unwrap();
        assert!(!name.is_empty());
        assert!(!row["doc"].as_str().unwrap().is_empty());
        assert!(row["pure"].is_boolean());
        let response = call(name, json!({}));
        assert!(response["ok"].is_boolean(), "{name}: {response}");
        assert!(
            !response["error"]
                .as_str()
                .unwrap_or("")
                .contains("unknown op"),
            "{name}: {response}"
        );
    }
    let caps = call("capabilities", json!({}));
    assert_eq!(caps["value"]["library"], "vectorcraft");
    assert_eq!(caps["value"]["version"], env!("CARGO_PKG_VERSION"));
    assert_eq!(
        caps["value"]["reference"],
        "0f8040ef59b88d33dd97563dbb87e8efedf046ff"
    );
}

#[test]
fn errors_are_envelopes() {
    assert!(
        call("missing", json!({}))["error"]
            .as_str()
            .unwrap()
            .contains("unknown op")
    );
    let op = CString::new("ops").unwrap();
    for (operation, request) in [
        (std::ptr::null(), op.as_ptr()),
        (op.as_ptr(), std::ptr::null()),
    ] {
        let ptr = hive_call(operation, request);
        let value: Value =
            serde_json::from_str(unsafe { CStr::from_ptr(ptr) }.to_str().unwrap()).unwrap();
        assert_eq!(value["ok"], false);
        assert!(value["error"].as_str().unwrap().contains("null"));
        hive_free(ptr);
    }
    for text in ["{broken", "[]", "null"] {
        let request = CString::new(text).unwrap();
        let ptr = hive_call(op.as_ptr(), request.as_ptr());
        let value: Value =
            serde_json::from_str(unsafe { CStr::from_ptr(ptr) }.to_str().unwrap()).unwrap();
        assert_eq!(value["ok"], false);
        hive_free(ptr);
    }
    let bad = [0xffu8, 0];
    for (operation, request) in [
        (op.as_ptr(), bad.as_ptr().cast::<c_char>()),
        (bad.as_ptr().cast::<c_char>(), op.as_ptr()),
    ] {
        let ptr = hive_call(operation, request);
        let value: Value =
            serde_json::from_str(unsafe { CStr::from_ptr(ptr) }.to_str().unwrap()).unwrap();
        assert_eq!(value["ok"], false);
        assert!(value["error"].as_str().unwrap().contains("UTF-8"));
        hive_free(ptr);
    }
    hive_free(std::ptr::null_mut());
}

/// Tests that change the one process-wide document hold this so `cargo test`'s parallel test
/// threads cannot interleave their edits.
static DOCUMENT: std::sync::Mutex<()> = std::sync::Mutex::new(());

fn objects(inspect: &Value) -> usize {
    match &inspect["objects"] {
        Value::Array(rows) => rows.len(),
        Value::Number(n) => n.as_u64().unwrap() as usize,
        other => panic!("unexpected `objects` in {inspect}: {other}"),
    }
}

#[test]
fn concurrent_callers_share_one_engine() {
    let _held = DOCUMENT.lock().unwrap_or_else(|e| e.into_inner());
    let created = call(
        "engine.execute",
        json!({"command": "file.new", "params": {}}),
    );
    assert_eq!(created["ok"], true, "file.new: {created}");
    let base = call("document.inspect", json!({}));
    assert_eq!(base["ok"], true, "{base}");
    let base = objects(&base["value"]);
    const THREADS: usize = 4;
    let workers: Vec<_> = (0..THREADS)
        .map(|i| {
            std::thread::spawn(move || {
                let x = 10 + 20 * i;
                let r = call(
                    "engine.execute",
                    json!({"command": "shape.rectangle",
                    "params": {"x": x, "y": 10, "width": 15, "height": 15}}),
                );
                assert_eq!(r["ok"], true, "thread {i} rectangle: {r}");
                for _ in 0..5 {
                    let seen = call("document.inspect", json!({}));
                    assert_eq!(seen["ok"], true, "thread {i} inspect: {seen}");
                    assert!(
                        objects(&seen["value"]) > base,
                        "thread {i} sees its own shape: {seen}"
                    );
                    let caps = call("capabilities", json!({}));
                    assert_eq!(caps["ok"], true, "thread {i} capabilities: {caps}");
                }
            })
        })
        .collect();
    for worker in workers {
        worker.join().expect("worker thread panicked");
    }
    // Every worker's rectangle landed in the same document the main thread inspects.
    let after = call("document.inspect", json!({}));
    assert_eq!(after["ok"], true, "{after}");
    assert_eq!(objects(&after["value"]), base + THREADS, "{after}");
}

#[test]
fn document_flow() {
    let _held = DOCUMENT.lock().unwrap_or_else(|e| e.into_inner());
    let start = std::time::Instant::now();
    let execute = |command: &str, params: Value| {
        let r = call(
            "engine.execute",
            json!({"command": command, "params": params}),
        );
        assert_eq!(r["ok"], true, "{command}: {r}");
        r["value"].clone()
    };
    execute("file.new", json!({}));
    execute(
        "shape.rectangle",
        json!({"x":10,"y":10,"width":80,"height":40}),
    );
    execute(
        "shape.ellipse",
        json!({"x":100,"y":100,"width":60,"height":60}),
    );
    let before = execute("document.inspect", json!({}));
    let path = std::env::temp_dir().join(format!(
        "vectorcraft-cabi-{}.vectorcraft",
        std::process::id()
    ));
    let saved = call("app.save", json!({"path": path}));
    assert_eq!(saved["ok"], true, "save: {saved}");
    let reopened = call("app.open", json!({"path": path}));
    assert_eq!(reopened["ok"], true, "reopen: {reopened}");
    let after = call("document.inspect", json!({}));
    assert_eq!(after["ok"], true, "inspect after reopen: {after}");
    assert_eq!(before["objects"], after["value"]["objects"]);
    std::fs::remove_file(path).unwrap();
    let rendered = call("ui.render", json!({"scale":0.25}));
    assert_eq!(rendered["ok"], true, "{rendered}");
    let v = &rendered["value"];
    let png = base64::Engine::decode(
        &base64::engine::general_purpose::STANDARD,
        v["pngBase64"].as_str().unwrap(),
    )
    .unwrap();
    assert_eq!(&png[..8], b"\x89PNG\r\n\x1a\n");
    assert_eq!(
        u32::from_be_bytes(png[16..20].try_into().unwrap()),
        v["width"].as_u64().unwrap() as u32
    );
    assert_eq!(
        u32::from_be_bytes(png[20..24].try_into().unwrap()),
        v["height"].as_u64().unwrap() as u32
    );
    eprintln!(
        "end-to-end flow: {:?}, {}x{}, {} PNG bytes",
        start.elapsed(),
        v["width"],
        v["height"],
        png.len()
    );
}

#[test]
fn exported_symbols_load() {
    // `cargo test` links the cdylib into `<target>/<profile>/deps`, beside this test binary;
    // `cargo build` additionally uplifts it to `<target>/<profile>`.
    let deps = std::env::current_exe().unwrap().parent().unwrap().to_path_buf();
    let library = [deps.clone(), deps.parent().unwrap().to_path_buf()]
        .into_iter()
        .map(|dir| dir.join("libvectorcraft.so"))
        .find(|candidate| candidate.exists())
        .unwrap_or_else(|| panic!("libvectorcraft.so not found near {}", deps.display()));
    let lib = unsafe { libloading::Library::new(&library) }
        .unwrap_or_else(|e| panic!("{}: {e}", library.display()));
    unsafe {
        let _: libloading::Symbol<
            unsafe extern "C" fn(*const c_char, *const c_char) -> *mut c_char,
        > = lib.get(b"hive_call").unwrap();
        let _: libloading::Symbol<unsafe extern "C" fn(*mut c_char)> =
            lib.get(b"hive_free").unwrap();
    }
}
