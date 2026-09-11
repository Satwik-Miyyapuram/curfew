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
            // The path travels with the config, because the Plan page has to name it. It used to
            // render a placeholder — `curfew add-window <config> …` — which is the one instruction
            // on that page a user cannot act on: they do not know what `<config>` is. Telling them
            // matters more than it looks, because the file the service reads lives under
            // `%ProgramData%` and the README's examples all say `curfew.toml`, so editing the file
            // in the current directory changes nothing and says nothing.
            let shown = path.display().to_string();
            match std::fs::read_to_string(&path).map_err(|e| format!("{shown}: {e}")).and_then(
                |text| curfew_core::Config::from_toml(&text).map_err(|e| format!("{shown}: {e}")),
            ) {
                Ok(config) => serde_json::json!({ "ok": true, "value": config, "path": shown }),
                Err(detail) => serde_json::json!({
                    "ok": false,
                    "kind": "error",
                    "detail": detail,
                    "path": shown,
                }),
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
