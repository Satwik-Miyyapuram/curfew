//! The window, the webview, and the one bridge between the page and the service.

use curfew_win::ipc::{ask, Request};
use serde::Deserialize;
use std::path::{Path, PathBuf};
use tao::event::{Event, StartCause, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder};
use tao::window::WindowBuilder;
use wry::WebViewBuilder;

/// The document. Compiled in rather than loaded from disk: a window that reads its own UI from a
/// file next to the binary is a window whose UI an unprivileged process can rewrite, and this one
/// is allowed to send `End` and `Unlock` to the service.
const PAGE: &str = include_str!("../ui/app.html");

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

/// Answer one call. Runs off the UI thread; the pipe is local and fast, but "fast" is not "always".
fn answer(call: Call) -> serde_json::Value {
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
            match std::fs::read_to_string(&path)
                .map_err(|e| format!("{}: {e}", path.display()))
                .and_then(|text| {
                    curfew_core::Config::from_toml(&text)
                        .map_err(|e| format!("{}: {e}", path.display()))
                }) {
                Ok(config) => serde_json::json!({ "ok": true, "value": config }),
                Err(detail) => {
                    serde_json::json!({ "ok": false, "kind": "error", "detail": detail })
                }
            }
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
        .expect("curfew-app: the window could not be created");

    let proxy = event_loop.create_proxy();
    let webview = WebViewBuilder::new()
        .with_html(PAGE)
        // The page is a local string with no origin and the design loads nothing remote, so there
        // is nothing here for a network permission to be for.
        .with_devtools(cfg!(debug_assertions))
        .with_ipc_handler(move |request| {
            let body = request.body().to_string();
            let proxy = proxy.clone();
            // Off the UI thread: a service that has wedged must not take the window with it.
            std::thread::spawn(move || {
                let script = match serde_json::from_str::<Envelope>(&body) {
                    Ok(envelope) => reply_script(envelope.id, &answer(envelope.call)),
                    // A malformed call is this program's bug, not the user's, and silence would
                    // leave the page waiting on a promise that never settles.
                    Err(e) => format!("console.error({:?});", e.to_string()),
                };
                let _ = proxy.send_event(Ev::Reply(script));
            });
        })
        .build(&window)
        .expect("curfew-app: WebView2 could not start");

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
