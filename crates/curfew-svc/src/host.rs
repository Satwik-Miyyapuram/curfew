//! The native-messaging host: the extension's only way to reach the service.
//!
//! The browser starts this process, talks to it over stdin and stdout, and kills it when the
//! extension goes away. It is deliberately a relay and nothing else — it decides nothing, it holds
//! no state, and every question it is asked is answered by the service (GAPS G1). An extension
//! someone has edited can therefore lie about which URL is open, which only blocks them harder, but
//! it cannot invent an allow, because it never gets to make one.

use curfew_win::extension::{frame, read_message, FromExtension, ToExtension};
use curfew_win::ipc::{Request, Response};
use std::io::Write;

/// Turn one message from the extension into the answer that goes back.
///
/// `ask` is passed in so this can be tested without a service: the interesting cases are a service
/// that is not running and a message that is not what it claims to be, and neither is comfortable
/// to produce for real.
pub fn respond(
    message: &[u8],
    ask: &mut impl FnMut(&Request) -> std::io::Result<Response>,
) -> Vec<u8> {
    let answer = match serde_json::from_slice::<FromExtension>(message) {
        Err(e) => ToExtension::Error { detail: e.to_string() },
        Ok(FromExtension::Beat { browser, url }) => match ask(&Request::Beat { browser, url }) {
            Ok(_) => ToExtension::Ok,
            Err(e) => ToExtension::Error { detail: e.to_string() },
        },
        Ok(FromExtension::Check { browser, url }) => match ask(&Request::Check { browser, url }) {
            Ok(Response::Verdict { blocked, reason }) => ToExtension::Verdict { blocked, reason },
            // A service that cannot be reached does not block the web. The service closes an
            // unwatched browser all by itself, so failing open here costs nothing: what it avoids
            // is a stopped service turning every tab into a block page, which is the failure that
            // would make people uninstall the extension and lose the layer entirely.
            Ok(other) => ToExtension::Error { detail: format!("unexpected answer: {other:?}") },
            Err(e) => ToExtension::Error { detail: e.to_string() },
        },
    };
    let body = serde_json::to_vec(&answer).unwrap_or_else(|_| b"{\"type\":\"ok\"}".to_vec());
    frame(&body)
}

/// Relay until the browser closes the pipe.
pub fn run() -> i32 {
    let mut input = std::io::stdin().lock();
    let mut output = std::io::stdout().lock();
    let mut ask = |request: &Request| curfew_win::ipc::ask(request);
    loop {
        match read_message(&mut input) {
            Ok(None) => return 0,
            // A framing error means the other end is not the browser, or is not well. Either way
            // the stream cannot be resynchronized, so the honest move is to stop.
            Err(_) => return 1,
            Ok(Some(message)) => {
                if output.write_all(&respond(&message, &mut ask)).is_err() {
                    return 1;
                }
                let _ = output.flush();
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use curfew_win::extension::MAX_MESSAGE;

    fn answer(
        message: &[u8],
        mut ask: impl FnMut(&Request) -> std::io::Result<Response>,
    ) -> ToExtension {
        let framed = respond(message, &mut ask);
        serde_json::from_slice(&framed[4..]).expect("the reply was not valid JSON")
    }

    #[test]
    fn a_check_is_answered_by_the_service_and_not_by_the_host() {
        let message = br#"{"type":"check","browser":"chrome.exe","url":"https://x.test/a"}"#;

        let reply = answer(message, |request| {
            assert!(matches!(request, Request::Check { .. }), "the host decided this itself");
            Ok(Response::Verdict { blocked: true, reason: Some("Blocked by focus.".into()) })
        });

        assert_eq!(
            reply,
            ToExtension::Verdict { blocked: true, reason: Some("Blocked by focus.".into()) }
        );
    }

    #[test]
    fn a_heartbeat_is_passed_straight_through() {
        let reply = answer(br#"{"type":"beat","browser":"firefox.exe"}"#, |request| {
            assert_eq!(request, &Request::Beat { browser: "firefox.exe".into(), url: None });
            Ok(Response::Ok)
        });

        assert_eq!(reply, ToExtension::Ok);
    }

    #[test]
    fn a_service_that_cannot_be_reached_does_not_turn_every_tab_into_a_block_page() {
        // The service closes an unwatched browser on its own, so failing open here loses nothing —
        // and failing closed would mean a stopped service breaks the whole web.
        let reply =
            answer(br#"{"type":"check","browser":"chrome.exe","url":"https://x.test/"}"#, |_| {
                Err(std::io::Error::other("the pipe is not there"))
            });

        assert!(matches!(reply, ToExtension::Error { .. }));
    }

    #[test]
    fn nonsense_on_the_pipe_is_an_error_rather_than_a_panic() {
        let reply = answer(b"not json at all", |_| panic!("the service must not be asked"));
        assert!(matches!(reply, ToExtension::Error { .. }));

        let reply = answer(br#"{"type":"end","id":"win-1"}"#, |_| {
            panic!("the extension cannot ask for anything but a beat or a check")
        });
        assert!(matches!(reply, ToExtension::Error { .. }));
    }

    #[test]
    fn every_reply_is_framed_the_way_the_browser_reads_them() {
        let framed =
            respond(br#"{"type":"beat","browser":"chrome.exe"}"#, &mut |_| Ok(Response::Ok));

        let length = u32::from_ne_bytes(framed[..4].try_into().unwrap()) as usize;
        assert_eq!(length, framed.len() - 4);
        assert!(length < MAX_MESSAGE);
    }
}
