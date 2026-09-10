//! The Curfew window.
//!
//! Everything Curfew enforces is enforced by the service, which runs as SYSTEM and keeps running
//! when this is closed. This process is a reader and a asker: it shows what the service says, and
//! it sends the same handful of messages the tray and the command line can send. That is the whole
//! privilege story, and it is why the window can be an ordinary unelevated program.
//!
//! The page is drawn by WebView2 — the browser control Windows already ships — because the design
//! for this window is HTML in `design/win/`, and re-drawing it in raw Win32 would mean maintaining
//! the same layout twice and having it agree. The webview is given no network permission and no
//! remote origin: the document is a string compiled into this binary.

#[cfg(windows)]
mod app;

#[cfg(windows)]
fn main() {
    app::run();
}

#[cfg(not(windows))]
fn main() {
    eprintln!("curfew-app: the window is a Windows thing; `curfew status` works everywhere");
}
