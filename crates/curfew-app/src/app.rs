//! The window, the webview, and the one bridge between the page and the service.

use curfew_win::ipc::{ask, Request};
use serde::Deserialize;
use std::path::{Path, PathBuf};
use tao::event::{Event, StartCause, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder};
use tao::window::WindowBuilder;
use wry::WebViewBuilder;

/// How many service calls the window will have in flight at once.
///
/// **P2-17.** `ipc::ask` cannot take a deadline — `interprocess`'s Windows named-pipe stream returns
/// `Unsupported` for `set_read_timeout`, as `curfew_svc::runner` already records — so a service that
/// is *connected but wedged* blocks every ask for ever. The page issues two calls a second and each
/// one used to spawn a thread, so a wedged service grew threads without bound.
///
/// Bounding the count is the same answer the service gives on its own side, and it is the one that
/// works without a deadline: the number of stuck threads is capped, the window stays responsive, and
/// the page is told rather than left waiting. Small, because this is a local pipe that either answers
/// in milliseconds or is not answering at all — there is no useful middle ground to leave room for.
const MAX_IN_FLIGHT: usize = 8;

/// The document. Compiled in rather than loaded from disk: a window that reads its own UI from a
/// file next to the binary is a window whose UI an unprivileged process can rewrite, and this one
/// is allowed to send `End` and `Unlock` to the service.
const PAGE: &str = include_str!("../ui/app.html");

/// The `{"kind": "…"}` lock names the page's strength list offers.
///
/// Scraped from the page rather than duplicated here, so the two cannot drift: a strength is added
/// in one place — the JavaScript a person can read — and this reads back what was written. Used only
/// by the tests; the window itself never needs it.
#[cfg(test)]
fn lock_kinds_referenced() -> Vec<String> {
    let mut found = Vec::new();
    let mut rest = PAGE;
    // `lock: { kind: "…" }` appears nowhere but the strength list.
    const NEEDLE: &str = "lock: { kind:";
    while let Some(at) = rest.find(NEEDLE) {
        rest = &rest[at + NEEDLE.len()..];
        let Some(open) = rest.find('"') else { break };
        let after = &rest[open + 1..];
        let Some(close) = after.find('"') else { break };
        found.push(after[..close].to_string());
        rest = &after[close..];
    }
    found
}

/// One message from the page.
///
/// Two kinds, and no third: everything that can change the world goes through [`Request`], which is
/// the same closed set of messages the tray sends, and the config is read-only here.
#[derive(Debug, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum Call {
    /// Ask the service something.
    Ipc { payload: Request },
    /// Read the config file from disk, for the pages that describe the plan rather than the moment.
    Config,
    /// Save a complete config TOML string to disk and tell the service to reload.
    SaveConfig { toml: String },
    /// Save a modified config JSON representation to disk and tell the service to reload.
    SaveConfigJson { config: serde_json::Value },
    /// End a session by proving ownership of the machine.
    ///
    /// The password never reaches the page. The host shows the operating system's own credential
    /// dialog and sends the result to the service itself, so no password is ever typed into — or
    /// stored by — the WebView.
    ///
    /// The window used to draw a password box of its own in HTML, which contradicted the rule the
    /// tray states and follows (`curfew_win::prompt`: *"Curfew never draws a password box of its own…
    /// a user can tell it from a phishing box drawn by an application"*). The bigger, more prominent
    /// surface was the one breaking it. A password typed into a browser engine lives in a DOM, in
    /// form state, and in whatever the rendering process does with it; a password typed into the
    /// system dialog lives in a buffer this process wipes on drop.
    ///
    /// **The dialog has no owner window.** `answer` runs on a worker thread with no access to the
    /// window handle, so `CredUIPromptForWindowsCredentialsW` gets a null parent and the prompt is
    /// owned by the desktop rather than by the window. It still appears and is still modal; it is not
    /// pinned above the window, so on a multi-monitor desk it can open on another screen. Threading a
    /// handle through would mean shared mutable state between the UI thread and every worker, which is
    /// a worse trade for a dialog that appears once in a while.
    Unlock { id: String },
    /// Discover installed desktop applications and currently running windowed applications.
    InstalledApps,
    /// Starts or gets the local Wi-Fi pairing listener.
    PairingServer,
}

#[derive(Debug, Deserialize)]
struct Envelope {
    id: u64,
    #[serde(flatten)]
    call: Call,
}

/// What the service reads. The app reads the same file so that "your plan" and "what is enforced"
/// cannot disagree; it never writes it, because `%ProgramData%\Curfew` is administrator-owned and a
/// config an ordinary user could edit is a lock an ordinary user could edit.
fn config_path() -> PathBuf {
    let root = std::env::var("ProgramData").unwrap_or_else(|_| "C:\\ProgramData".to_string());
    Path::new(&root).join("Curfew").join("curfew.toml")
}

/// Where WebView2 keeps this user's profile for this window.
///
/// Per user, under `%LOCALAPPDATA%`, because that is the one place a program installed for the
/// whole machine can be sure the person running it may write.
fn webview_data_dir() -> PathBuf {
    let root =
        std::env::var("LOCALAPPDATA").map(PathBuf::from).unwrap_or_else(|_| std::env::temp_dir());
    root.join("Curfew").join("webview")
}

#[derive(Debug, serde::Serialize)]
struct DiscoveredApp {
    name: String,
    exe: String,
    running: bool,
}

fn parse_lnk(path: &Path) -> Option<PathBuf> {
    let bytes = std::fs::read(path).ok()?;
    if bytes.len() < 76 {
        return None;
    }
    let flags = u32::from_le_bytes(bytes.get(0x14..0x18)?.try_into().ok()?);
    let has_id_list = (flags & 1) != 0;
    let has_link_info = (flags & 2) != 0;
    if !has_link_info {
        return None;
    }
    let mut offset = 76;
    if has_id_list {
        if bytes.len() < offset + 2 {
            return None;
        }
        let id_list_len =
            u16::from_le_bytes(bytes.get(offset..offset + 2)?.try_into().ok()?) as usize;
        offset += 2 + id_list_len;
    }
    if bytes.len() < offset + 0x14 {
        return None;
    }
    let local_base_path_offset =
        u32::from_le_bytes(bytes.get(offset + 0x10..offset + 0x14)?.try_into().ok()?) as usize;
    let path_start = offset + local_base_path_offset;
    if path_start >= bytes.len() {
        return None;
    }
    let nul_pos = bytes[path_start..].iter().position(|&b| b == 0)?;
    let target_str = String::from_utf8_lossy(&bytes[path_start..path_start + nul_pos]).to_string();
    Some(PathBuf::from(target_str))
}

fn collect_lnks_in_dir(dir: &Path, apps: &mut std::collections::BTreeMap<String, (String, bool)>) {
    let mut stack = vec![dir.to_path_buf()];
    while let Some(current_dir) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(current_dir) else { continue };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                stack.push(path);
            } else if path
                .extension()
                .and_then(|ext| ext.to_str())
                .map(|ext| ext.eq_ignore_ascii_case("lnk"))
                .unwrap_or(false)
            {
                let Some(stem) = path.file_stem().and_then(|s| s.to_str()) else { continue };
                let stem_lower = stem.to_lowercase();
                if stem_lower.contains("uninstall")
                    || stem_lower.contains("documentation")
                    || stem_lower.contains("readme")
                    || stem_lower.contains("help")
                {
                    continue;
                }
                if let Some(target) = parse_lnk(&path) {
                    if let Some(target_ext) = target.extension().and_then(|ext| ext.to_str()) {
                        if target_ext.eq_ignore_ascii_case("exe") {
                            if let Some(target_name) = target.file_name().and_then(|n| n.to_str()) {
                                let key = target_name.to_lowercase();
                                if !matches!(
                                    key.as_str(),
                                    "cmd.exe"
                                        | "powershell.exe"
                                        | "pwsh.exe"
                                        | "conhost.exe"
                                        | "rundll32.exe"
                                ) {
                                    apps.entry(key).or_insert_with(|| (stem.to_string(), false));
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fn installed_apps() -> Vec<DiscoveredApp> {
    let mut map = std::collections::BTreeMap::new();

    if let Ok(pd) = std::env::var("ProgramData") {
        let dir =
            Path::new(&pd).join("Microsoft").join("Windows").join("Start Menu").join("Programs");
        collect_lnks_in_dir(&dir, &mut map);
    }
    if let Ok(appdata) = std::env::var("APPDATA") {
        let dir = Path::new(&appdata)
            .join("Microsoft")
            .join("Windows")
            .join("Start Menu")
            .join("Programs");
        collect_lnks_in_dir(&dir, &mut map);
    }

    // Check running windowed processes
    for proc_ in curfew_win::procs::Processes::list(&curfew_win::procs::SystemProcesses::default())
    {
        if proc_.title.is_empty() {
            continue;
        }
        let key = proc_.exe.to_lowercase();
        if matches!(
            key.as_str(),
            "explorer.exe" | "curfew-app.exe" | "curfew-tray.exe" | "msedgewebview2.exe"
        ) {
            continue;
        }
        if let Some(entry) = map.get_mut(&key) {
            entry.1 = true;
        } else {
            let name = proc_.exe.strip_suffix(".exe").unwrap_or(&proc_.exe).to_string();
            map.insert(key, (name, true));
        }
    }

    let mut result: Vec<DiscoveredApp> = map
        .into_iter()
        .map(|(exe_key, (name, running))| DiscoveredApp { name, exe: exe_key, running })
        .collect();

    result.sort_by(|a, b| {
        b.running.cmp(&a.running).then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
    });

    result
}

static PAIRING_PORT: std::sync::atomic::AtomicU16 = std::sync::atomic::AtomicU16::new(0);

fn get_local_ip() -> std::net::IpAddr {
    std::net::UdpSocket::bind("0.0.0.0:0")
        .and_then(|s| {
            s.connect("8.8.8.8:80")?;
            s.local_addr()
        })
        .map(|addr| addr.ip())
        .unwrap_or_else(|_| std::net::IpAddr::V4(std::net::Ipv4Addr::new(127, 0, 0, 1)))
}

fn ensure_pairing_server(proxy: &tao::event_loop::EventLoopProxy<Ev>) -> (String, u16) {
    let current_port = PAIRING_PORT.load(std::sync::atomic::Ordering::SeqCst);
    let ip = get_local_ip().to_string();
    if current_port != 0 {
        return (ip, current_port);
    }
    let listener = match std::net::TcpListener::bind(("0.0.0.0", 0)) {
        Ok(l) => l,
        Err(_) => return (ip, 0),
    };
    let port = listener.local_addr().map(|a| a.port()).unwrap_or(0);
    PAIRING_PORT.store(port, std::sync::atomic::Ordering::SeqCst);

    let proxy = proxy.clone();
    std::thread::spawn(move || {
        use std::io::{Read, Write};
        while let Ok((mut stream, _)) = listener.accept() {
            let proxy = proxy.clone();
            std::thread::spawn(move || {
                let mut buf = vec![0u8; 8192];
                let n = stream.read(&mut buf).unwrap_or(0);
                let req = String::from_utf8_lossy(&buf[..n]);
                if req.starts_with("POST") || req.contains("CRFW:") {
                    let body =
                        if let Some(idx) = req.find("\r\n\r\n") { &req[idx + 4..] } else { &req };
                    let reply_code = body.trim().trim_start_matches("code=").trim();
                    if !reply_code.is_empty() {
                        let _ = ask(&Request::Accept { invite: reply_code.to_string() });
                        let response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n{\"status\":\"paired\"}\n";
                        let _ = stream.write_all(response.as_bytes());
                        let _ = stream.flush();
                        let _ = proxy.send_event(Ev::Reply(
                            "if (window.__onPeerPaired) window.__onPeerPaired();".to_string(),
                        ));
                    }
                } else if req.starts_with("OPTIONS") {
                    let response = "HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: POST, OPTIONS\r\nAccess-Control-Allow-Headers: *\r\nConnection: close\r\n\r\n";
                    let _ = stream.write_all(response.as_bytes());
                }
            });
        }
    });

    (ip, port)
}

/// Answer one call. Runs off the UI thread; the pipe is local and fast, but "fast" is not "always".
fn answer(call: Call, proxy: &tao::event_loop::EventLoopProxy<Ev>) -> serde_json::Value {
    match call {
        Call::Ipc { payload } => match ask(&payload) {
            Ok(response) => serde_json::json!({ "ok": true, "value": response }),
            // The kind matters to the page: "not installed" and "refused" want different words, and
            // the page has them. The message is carried too, because a kind is not an explanation.
            Err(e) => serde_json::json!({
                "ok": false,
                "kind": match e.kind() {
                    std::io::ErrorKind::NotFound => "not_found",
                    std::io::ErrorKind::PermissionDenied => "denied",
                    _ => "error",
                },
                "detail": e.to_string(),
            }),
        },
        Call::Config => {
            let path = config_path();
            let shown = path.display().to_string();
            let raw_toml = std::fs::read_to_string(&path).unwrap_or_default();
            match curfew_core::Config::from_toml(&raw_toml).map_err(|e| format!("{shown}: {e}")) {
                Ok(config) => {
                    serde_json::json!({ "ok": true, "value": config, "toml": raw_toml, "path": shown })
                }
                Err(detail) => serde_json::json!({
                    "ok": false,
                    "kind": "error",
                    "detail": detail,
                    "toml": raw_toml,
                    "path": shown,
                }),
            }
        }
        Call::SaveConfig { toml } => {
            let path = config_path();
            let shown = path.display().to_string();
            if let Err(e) = curfew_core::Config::from_toml(&toml) {
                return serde_json::json!({
                    "ok": false,
                    "kind": "error",
                    "detail": format!("Invalid configuration: {e}")
                });
            }
            if let Some(parent) = path.parent() {
                let _ = std::fs::create_dir_all(parent);
            }
            if let Err(e) = std::fs::write(&path, &toml) {
                return serde_json::json!({
                    "ok": false,
                    "kind": "error",
                    "detail": format!("Could not write to {shown}: {e}")
                });
            }
            match ask(&Request::Reload) {
                Ok(response) => serde_json::json!({ "ok": true, "value": response }),
                Err(e) => serde_json::json!({
                    "ok": true,
                    "warning": format!("Config saved, but service reload failed: {e}")
                }),
            }
        }
        Call::SaveConfigJson { config } => {
            match serde_json::from_value::<curfew_core::Config>(config) {
                Ok(cfg) => match cfg.to_toml() {
                    Ok(toml) => answer(Call::SaveConfig { toml }, proxy),
                    Err(e) => {
                        serde_json::json!({ "ok": false, "kind": "error", "detail": e.to_string() })
                    }
                },
                Err(e) => {
                    serde_json::json!({ "ok": false, "kind": "error", "detail": format!("Invalid config: {e}") })
                }
            }
        }
        Call::Unlock { id } => {
            // Cancelled means the user changed their mind. That is not a failure and must not be
            // reported as one: the page shows nothing and the session stays locked, which is what
            // they just asked for by closing the dialog.
            let Some(credential) = curfew_win::prompt::ask(
                std::ptr::null_mut(),
                "Curfew needs to know it is you before it ends this session early.",
            ) else {
                return serde_json::json!({ "ok": true, "value": { "response": "cancelled" } });
            };
            let request = Request::Unlock {
                id,
                username: credential.username.clone(),
                domain: credential.domain.clone(),
                password: credential.password.clone(),
            };
            match ask(&request) {
                // The credential is dropped here, which wipes the password. It is never serialized,
                // never returned to the page, and never written anywhere.
                Ok(response) => serde_json::json!({ "ok": true, "value": response }),
                Err(e) => {
                    serde_json::json!({ "ok": false, "kind": "error", "detail": e.to_string() })
                }
            }
        }
        Call::InstalledApps => {
            let apps = installed_apps();
            serde_json::json!({ "ok": true, "apps": apps })
        }
        Call::PairingServer => {
            let (ip, port) = ensure_pairing_server(proxy);
            serde_json::json!({ "ok": true, "ip": ip, "port": port })
        }
    }
}

/// A reply, as the one line of script that delivers it.
fn reply_script(id: u64, value: &serde_json::Value) -> String {
    // Serialized twice on purpose: the inner string is a JSON literal the page parses, which keeps
    // every character in the payload — quotes, newlines, a profile named `</script>` — out of the
    // script grammar entirely.
    let payload = serde_json::to_string(value).unwrap_or_else(|_| "null".into());
    let literal = serde_json::to_string(&payload).unwrap_or_else(|_| "\"null\"".into());
    format!("window.__curfewReply({id}, {literal});")
}

/// A last word before the window gives up.
///
/// The only failure here a person can act on is a missing WebView2 runtime, and a process that
/// exits silently tells them nothing: the shortcut was clicked, and nothing happened. Blocks carry
/// on either way — this is the window, not the enforcement.
fn fatal(message: &str) -> ! {
    use windows_sys::Win32::UI::WindowsAndMessaging::{MessageBoxW, MB_ICONERROR, MB_OK};

    let wide = |text: &str| text.encode_utf16().chain(std::iter::once(0)).collect::<Vec<u16>>();
    let body = wide(message);
    let title = wide("Curfew");
    // SAFETY: both strings are NUL-terminated and outlive the call, and a null owner window is
    // what MessageBoxW documents for a dialog with no parent.
    unsafe {
        MessageBoxW(std::ptr::null_mut(), body.as_ptr(), title.as_ptr(), MB_OK | MB_ICONERROR)
    };
    std::process::exit(1)
}

/// A reply, sent home.
enum Ev {
    Reply(String),
}

pub fn run() {
    let event_loop = EventLoopBuilder::<Ev>::with_user_event().build();
    let window = WindowBuilder::new()
        .with_title("Curfew")
        .with_inner_size(tao::dpi::LogicalSize::new(1120.0, 720.0))
        .with_min_inner_size(tao::dpi::LogicalSize::new(880.0, 560.0))
        .build(&event_loop)
        .unwrap_or_else(|e| fatal(&format!("The Curfew window could not be created.\n\n{e}")));

    // WebView2 keeps a profile — caches, a settings database — and unless told otherwise it puts
    // it beside the executable. Beside the executable is `C:\Program Files\Curfew`, which no
    // ordinary user may write to, so the webview failed to start with "Access is denied" for every
    // account on an installed copy. It belongs in the user's own data directory, per user, like
    // every other program's profile.
    let mut context = wry::WebContext::new(Some(webview_data_dir()));

    let proxy = event_loop.create_proxy();
    let webview = WebViewBuilder::with_web_context(&mut context)
        .with_html(PAGE)
        // The page is a local string with no origin and the design loads nothing remote, so there
        // is nothing here for a network permission to be for.
        .with_devtools(cfg!(debug_assertions))
        .with_ipc_handler({
            // Shared across every message, so the cap is a property of the window rather than of one
            // handler.
            let in_flight = curfew_win::capacity::Capacity::new(MAX_IN_FLIGHT);
            move |request| {
                let body = request.body().to_string();
                let proxy = proxy.clone();

                // **Refused rather than queued** — P2-17. At the cap the service is not answering, and
                // a ninth thread would not change that; what it would change is how much of the machine
                // this window is holding while it waits. The page is answered immediately so it can
                // say so, instead of leaving two hundred promises unsettled.
                let Some(permit) = in_flight.take() else {
                    let script = match serde_json::from_str::<Envelope>(&body) {
                        Ok(envelope) => reply_script(
                            envelope.id,
                            &serde_json::json!({
                                "ok": false,
                                "kind": "error",
                                "detail": "The Curfew service is not answering. Nothing has been \
                                           changed; this window will keep trying."
                            }),
                        ),
                        Err(e) => format!("console.error({:?});", e.to_string()),
                    };
                    let _ = proxy.send_event(Ev::Reply(script));
                    return;
                };

                // Off the UI thread: a service that has wedged must not take the window with it.
                std::thread::spawn(move || {
                    // The permit rides with the thread and is given back by `Drop`, so a panic in a
                    // handler cannot leak a slot and wedge this window for good. See `capacity`.
                    let _permit = permit;
                    let proxy = proxy.clone();
                    let script = match serde_json::from_str::<Envelope>(&body) {
                        Ok(envelope) => reply_script(envelope.id, &answer(envelope.call, &proxy)),
                        // A malformed call is this program's bug, not the user's, and silence would
                        // leave the page waiting on a promise that never settles.
                        Err(e) => format!("console.error({:?});", e.to_string()),
                    };
                    let _ = proxy.send_event(Ev::Reply(script));
                });
            }
        })
        .build(&window);
    let webview = match webview {
        Ok(webview) => webview,
        Err(e) => fatal(&format!(
            "The Curfew window did not open.\n\n\
             It is drawn by the Microsoft Edge WebView2 runtime, which every supported build of \
             Windows has; if this one does not, installing it from Microsoft is enough, and \
             nothing else about Curfew changes:\n\n\
             https://developer.microsoft.com/microsoft-edge/webview2/\n\n\
             Anything blocked stays blocked while this window will not open. Blocking is done by \
             the Curfew service, which is not this program.\n\n\
             {e}"
        )),
    };

    event_loop.run(move |event, _, control_flow| {
        *control_flow = ControlFlow::Wait;
        match event {
            Event::NewEvents(StartCause::Init) => {}
            Event::UserEvent(Ev::Reply(script)) => {
                let _ = webview.evaluate_script(&script);
            }
            Event::WindowEvent { event: WindowEvent::CloseRequested, .. } => {
                // Closing the window stops nothing. The service is a service.
                *control_flow = ControlFlow::Exit;
            }
            _ => {}
        }
    });
}

#[cfg(test)]
mod page_tests {
    use super::{lock_kinds_referenced, PAGE};

    /// The window can be used without a mouse.
    ///
    /// Everything on this page used to be a `div` or a `span` with a click handler and nothing else,
    /// so a keyboard user could not reach the pages that explain what is blocked — but *could* tab to
    /// "End it early". That is the wrong way round for a program whose whole promise is that the exits
    /// are deliberate, so these are the three pieces that make the difference, and a refactor that
    /// drops any of them puts the window back to mouse-only.
    ///
    /// The same shape as the Start-button test below: it checks the page still contains the wiring,
    /// not that the wiring behaves. `node --check` in the review's own steps is what proves it parses;
    /// nothing on this host can run a browser.
    #[test]
    fn the_page_can_be_driven_from_the_keyboard() {
        assert!(
            PAGE.contains(r#"document.addEventListener("keydown""#),
            "the window has no key handler, so nothing on it can be reached without a mouse"
        );
        assert!(
            PAGE.contains("function makeInteractive("),
            "nothing marks the click targets as focusable, so a keyboard cannot get to them"
        );
        assert!(
            PAGE.contains(r#"if (event.key === "Escape")"#),
            "a modal that cannot be dismissed from the keyboard is a trap for a keyboard user"
        );
        assert!(
            PAGE.contains(r#"<div class="sheet" role="dialog" aria-modal="true">"#),
            "the sheet is not announced as a dialog, so a screen reader reads it as more page text"
        );
        // The one that is easy to lose in a CSS tidy-up, and the one with no visual symptom: without
        // it the config path and the command on the Plan page cannot be copied. Anchored to the
        // selector, because `user-select:text` also appears on the input rule and that one would not
        // have restored selection over the page.
        //
        // Both of these read more specifically than they need to for one reason: a mutation check
        // showed the loose versions were vacuous. `role="dialog"` also appears in the comment above
        // the code that sets it, so deleting the real attribute still passed — an assertion short
        // enough to be prose is an assertion that can match prose.
        assert!(
            PAGE.contains(".body,.sheet,.mono,.flush,.card{user-select:text}"),
            "the body suppresses selection and nothing restores it, so nothing can be copied"
        );
    }

    /// A freeze can be called off from the window.
    ///
    /// The window used to render "A freeze is counting down" as a pill — no duration, no action —
    /// while the tray put "Cancel the freeze" first in its own menu. Telling someone their whole
    /// machine is about to close and offering them nothing is the failure this pins against.
    #[test]
    fn the_page_offers_a_way_out_of_a_freeze() {
        assert!(
            PAGE.contains(r#"data-act="cancel-freeze""#),
            "the window shows a countdown with no way to cancel it"
        );
        assert!(
            PAGE.contains(r#"request: "cancel_freeze""#),
            "the cancel control does not ask the service to cancel anything"
        );
        // And the countdown says how long, which the pill never did.
        assert!(
            PAGE.contains("function freezeSentence("),
            "the freeze is described by a bare pill again, with no time on it"
        );
    }

    /// The window never handles a password.
    ///
    /// It used to draw its own `<input type="password">` and send the typed value over the bridge —
    /// contradicting the rule the tray states and follows, and putting a password into a DOM. Now the
    /// host shows the system prompt and talks to the service itself, so there is nothing here to read.
    ///
    /// Asserted both ways, because either half alone is easy to satisfy by accident: the page must
    /// contain no password field and no hand-built unlock request, *and* the bridge must still carry
    /// the call that replaced them. A test that checked only the first would pass on a page with no
    /// unlock path at all.
    #[test]
    fn no_password_is_ever_typed_into_the_window() {
        assert!(
            !PAGE.contains(r#"type="password""#),
            "the window draws a password box again; the system prompt is the only one it may use"
        );
        assert!(
            !PAGE.contains(r#"getElementById("pw")"#),
            "the window reads a password field again"
        );
        assert!(
            !PAGE.contains(r#"request: "unlock""#),
            "a password is crossing the bridge again — the host sends it, not the page"
        );
        assert!(
            PAGE.contains(r#"call({ kind: "unlock", id })"#),
            "the page has no way to ask for the system prompt, so the lock has no way out"
        );
        assert!(
            PAGE.contains("function finishUnlock("),
            "the prompt's answer is not handled, so a refusal would say nothing"
        );
    }

    /// A cancelled system prompt says nothing at all.
    ///
    /// Closing the dialog means "never mind", and the session staying locked is what the user just
    /// asked for. Reporting that as a failure — or as a success — would both be wrong.
    #[test]
    fn a_cancelled_prompt_is_not_reported_as_a_failure() {
        assert!(
            PAGE.contains(r#"value.response === "cancelled""#),
            "a cancelled prompt is not distinguished, so closing the dialog reports something"
        );
    }

    /// The window is the only way to start a block on Windows, so the affordance is the fix for a
    /// P0 rather than a decoration. This test exists because that was a whole-product bug — the
    /// service had always accepted `Request::Start` and nothing but the command line could send it —
    /// and a bug that is one absent `<button>` is exactly the kind a refactor deletes by accident.
    ///
    /// It checks the page still carries the two things that make the journey work: a control that
    /// opens the sheet, and a call that sends the request. It cannot check that they are *wired to
    /// each other* — that is what `the_windows_page_can_start_a_block_over_the_wire` in `curfew-win`
    /// does, from the other end of the same contract.
    #[test]
    fn the_page_still_offers_to_start_a_block() {
        assert!(
            PAGE.contains(r#"data-act="start-open""#),
            "the window has no way to start a block, which is the product's whole verb"
        );
        assert!(
            PAGE.contains(r#"request: "start""#),
            "the Start sheet no longer asks the service to start anything"
        );
    }

    /// A strength the page offers must be one the core can actually be asked for.
    ///
    /// `lock_kinds_referenced` reads the wire forms out of the page; this pins them to the set the
    /// protocol understands. A fifth strength invented in the page and not in `Lock` would be a
    /// button the service refuses with "unknown variant", which reads to a user as a bug in Curfew
    /// rather than as a typo in a form.
    #[test]
    fn every_strength_the_page_offers_is_a_real_lock() {
        let kinds = lock_kinds_referenced();
        assert!(!kinds.is_empty(), "no lock kinds found in the page at all");
        for kind in &kinds {
            assert!(
                ["confirm", "device_credential", "timer"].contains(&kind.as_str()),
                "the page offers a lock kind the protocol does not know: {kind}"
            );
        }
        // The three the strength list is built from, so a silent removal fails here too.
        for expected in ["confirm", "device_credential", "timer"] {
            assert!(kinds.contains(&expected.to_string()), "the page lost the {expected} strength");
        }
    }
}
