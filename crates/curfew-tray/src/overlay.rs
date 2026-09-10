//! The screen that explains why an application just disappeared.
//!
//! Closing someone's window without saying anything is the worst thing a blocker does: it is
//! indistinguishable from a crash, it invites a second launch, and it makes the tool feel hostile
//! rather than agreed-to. So every close gets a sentence, and the sentence says what the user asked
//! for, when it ends, and how to get out early if there is a way.
//!
//! The overlay is deliberately not a wall. It does not cover the screen, does not take the keyboard,
//! and closes on a click or on its own after a few seconds — a modal that had to be dismissed would
//! be one more thing to fight with while trying to work.

use curfew_core::Timestamp;
use curfew_win::ipc::Status;
use std::collections::BTreeSet;

/// How long the notice stays up.
pub const DWELL_MS: u32 = 7_000;

/// How long a notice of `text` should stay up.
///
/// A block explanation is one sentence and seven seconds is generous for it. The same window now
/// carries the answers the menu used to hand to a `MessageBox` — what the service said, why it
/// could not be reached, the welcome — and some of those are a paragraph, which nobody finishes
/// in seven seconds. So the dwell follows the reading: a beat to notice it, then roughly the time
/// it takes to read at an unhurried pace, capped so a stray long string cannot leave a card parked
/// on the screen. A click still dismisses it at any point.
pub fn dwell_for(text: &str) -> u32 {
    let reading = text.chars().count() as u32 * 55;
    (DWELL_MS + reading).min(40_000)
}

/// Names closed since the previous poll.
///
/// The service reports what the last pass closed, and passes repeat every couple of seconds while
/// an application keeps relaunching. Only the names that were not there before are new, so a program
/// that respawns in a loop produces one explanation rather than a flood of them.
pub fn newly_closed(previous: &BTreeSet<String>, current: &BTreeSet<String>) -> Vec<String> {
    current.difference(previous).cloned().collect()
}

/// A pretty name for an executable: what the user calls the thing, not what the file is called.
fn app_name(exe: &str) -> String {
    let stem = exe.strip_suffix(".exe").unwrap_or(exe);
    let mut chars = stem.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().collect::<String>() + chars.as_str(),
        None => stem.to_string(),
    }
}

/// The sentence shown when `closed` were closed.
pub fn message(closed: &[String], status: &Status) -> String {
    let names: Vec<String> = closed.iter().map(|exe| app_name(exe)).collect();
    let what = match names.as_slice() {
        [] => return String::new(),
        [one] => one.clone(),
        [first, second] => format!("{first} and {second}"),
        many => format!("{} and {} others", many[0], many.len() - 1),
    };

    let profile = status.running.first().map(|s| s.profile.clone());
    let mut text = match profile {
        Some(profile) => format!("{what} is blocked during {profile}."),
        // No session and yet something was closed: the pass that closed it has since ended. Saying
        // "you asked for this" would be wrong, so the notice states the fact and stops.
        None => format!("{what} was closed by Curfew."),
    };

    let ends = status.running.iter().filter_map(|s| s.lock.ends_at).max();
    if let Some(ends) = ends {
        text.push_str(&format!("\n\nIt ends at {}.", crate::menu::when(ends)));
    }

    let locked = status
        .running
        .iter()
        .any(|s| s.lock.conditions.iter().any(|l| !matches!(l, curfew_core::Lock::Timer)));
    if locked {
        text.push_str("\nThe Curfew icon can end it early, or start the 24-hour release.");
    }
    text
}

/// The sentence shown when an app is being held behind a delay rule.
///
/// Said differently from a block on purpose. Nothing has been refused here — the app opens in a few
/// seconds whatever the user does — so the notice reads as a pause, not as a wall, and it never
/// mentions the release or the lock, neither of which has anything to do with it.
pub fn waiting_message(exe: &str, seconds_left: i64) -> String {
    let name = app_name(exe);
    match seconds_left.max(0) {
        0 | 1 => format!(
            "{name} opens in a moment.

This pause is what you asked for."
        ),
        n => format!(
            "{name} opens in {n} seconds.

This pause is what you asked for. Open it again when the wait is up."
        ),
    }
}

/// Which held apps are new since the last poll, so a wait produces one notice and not one per pass.
pub fn newly_delayed(
    previous: &BTreeSet<String>,
    current: &std::collections::BTreeMap<String, i64>,
) -> Vec<(String, i64)> {
    current
        .iter()
        .filter(|(exe, _)| !previous.contains(*exe))
        .map(|(exe, left)| (exe.clone(), *left))
        .collect()
}

/// Whether a notice is worth showing at all, given what is happening.
///
/// Nothing is shown for an application the user has not touched since the last one: repetition is
/// how a helpful notice becomes an enemy.
pub fn should_show(newly: &[String], last_shown: Option<Timestamp>, now: Timestamp) -> bool {
    if newly.is_empty() {
        return false;
    }
    // At most one notice every ten seconds, whatever happens. An application relaunching under a
    // different name each time must not be able to turn Curfew into a popup machine.
    match last_shown {
        Some(at) => now - at >= 10,
        None => true,
    }
}

#[cfg(windows)]
mod sys {
    use std::os::windows::ffi::OsStrExt as _;
    use windows_sys::Win32::Foundation::{HWND, LPARAM, LRESULT, RECT, WPARAM};
    use windows_sys::Win32::Graphics::Gdi::{
        BeginPaint, CreateFontW, CreatePen, CreateRoundRectRgn, CreateSolidBrush, DeleteObject,
        DrawTextW, EndPaint, FillRect, GetDC, ReleaseDC, RoundRect, SelectObject, SetBkMode,
        SetTextColor, SetWindowRgn, DT_CALCRECT, DT_LEFT, DT_NOPREFIX, DT_SINGLELINE, DT_TOP,
        DT_WORDBREAK, HDC, PAINTSTRUCT, PS_SOLID, TRANSPARENT,
    };
    use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        CreateWindowExW, DefWindowProcW, DestroyWindow, GetClientRect, GetSystemMetrics, KillTimer,
        RegisterClassW, SetTimer, ShowWindow, SM_CXSCREEN, SM_CYSCREEN, SW_SHOWNA, WM_DESTROY,
        WM_LBUTTONUP, WM_PAINT, WM_TIMER, WNDCLASSW, WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW,
        WS_EX_TOPMOST, WS_POPUP,
    };

    const CLOSE_TIMER: usize = 7;
    const WIDTH: i32 = 460;
    /// The shortest a card is ever drawn. A one-line notice in a tall card looks like something
    /// failed to load.
    const MIN_HEIGHT: i32 = 196;

    // The phone's palette, as GDI wants it: 0x00BBGGRR, not the 0xRRGGBB the rest of the project
    // writes. One app should not look like two, and this notice is the only Curfew surface most
    // Windows users ever see.
    /// `Palette.Surface` — the card the phone draws every panel on.
    const SURFACE: u32 = 0x0023_1B16;
    /// `Palette.Surface2` — what a control sits on when it has to read as a control.
    const SURFACE2: u32 = 0x002E_241D;
    /// `Palette.Line` — the hairline that separates a card from what is behind it.
    const LINE: u32 = 0x0041_332A;
    /// `Palette.Text`.
    const TEXT_COLOUR: u32 = 0x00F7_F1ED;
    /// `Palette.Muted`.
    const MUTED: u32 = 0x00AC_9A8D;
    /// `Palette.Live` — amber, which means "running" everywhere else in Curfew.
    const LIVE: u32 = 0x005A_A6F2;

    /// The amber stripe down the left edge, in pixels.
    const STRIPE: i32 = 4;
    /// The breathing room between the text and the edge of the card.
    const PAD: i32 = 22;
    /// The gap between the line that states the fact and the paragraphs under it.
    const GAP: i32 = 14;
    /// The two type sizes of the card, matching `design/win/Answer.dc.html`.
    ///
    /// They were four points larger each, from when this window said one short sentence about one
    /// closed application. At that size the service-not-installed answer opened with three lines of
    /// heavy title and read as a shouted error; the design has always had a 17px statement over 13px
    /// detail, and that is what a notice with a paragraph in it needs.
    const TITLE_PT: i32 = -17;
    const BODY_PT: i32 = -14;

    /// A body line beginning with a tab is a command, drawn as a chip rather than as prose.
    ///
    /// A command is the one part of these answers the reader has to reproduce exactly, and prose
    /// cannot show where it starts and ends: the wording used to put backticks around it, which is
    /// markdown leaking into a window that does not render markdown, and the reader was left to
    /// guess whether the backticks were part of what to type. A tab never appears in a sentence, so
    /// it costs the writer one character and needs no escaping.
    const COMMAND: char = '\t';

    thread_local! {
        static TEXT: std::cell::RefCell<Vec<u16>> = const { std::cell::RefCell::new(Vec::new()) };
    }

    fn wide(text: &str) -> Vec<u16> {
        std::ffi::OsStr::new(text).encode_wide().chain(std::iter::once(0)).collect()
    }

    /// One face, two weights: whatever this Windows calls its UI font, at the size asked for.
    fn font(height: i32, weight: i32) -> windows_sys::Win32::Graphics::Gdi::HFONT {
        let face = wide("Segoe UI Variable Text");
        // SAFETY: the face name outlives the call, and every numeric argument is a documented
        // constant (DEFAULT_CHARSET, CLEARTYPE_QUALITY, default precision and pitch).
        unsafe { CreateFontW(height, 0, 0, 0, weight, 0, 0, 0, 1, 0, 0, 5, 0, face.as_ptr()) }
    }

    /// The face a command is set in, so a command cannot be mistaken for a sentence.
    fn mono_font(height: i32) -> windows_sys::Win32::Graphics::Gdi::HFONT {
        let face = wide("Consolas");
        // SAFETY: as [`font`]; the face name outlives the call.
        unsafe { CreateFontW(height, 0, 0, 0, 400, 0, 0, 0, 1, 0, 0, 5, 0, face.as_ptr()) }
    }

    /// The notice split into the line that states the fact and the paragraphs that qualify it.
    ///
    /// [`super::message`] already writes it that way — one sentence, a blank line, then the detail —
    /// so the split is on the first blank line and nothing has to be restructured to draw it.
    fn split(text: &[u16]) -> (Vec<u16>, Vec<u16>) {
        let full = String::from_utf16_lossy(text);
        let full = full.trim_end_matches('\u{0}');
        match full.split_once("\n\n") {
            Some((head, rest)) => (wide(head), wide(rest.trim())),
            None => (wide(full), Vec::new()),
        }
    }

    /// Draw — or, with `paint` false, only measure — the paragraphs under the title.
    ///
    /// One function for both because the two have to agree to the pixel: the window is sized from
    /// the measurement before it exists, and a paint pass that lays the same text out differently
    /// either leaves a band of empty card or cuts off the last line. Returns the height used.
    ///
    /// SAFETY: `dc` must be a valid device context; every object this selects into it is put back
    /// and deleted before returning.
    unsafe fn draw_body(dc: HDC, left: i32, top: i32, right: i32, body: &str, paint: bool) -> i32 {
        let prose = font(BODY_PT, 400);
        let mono = mono_font(BODY_PT);
        let previous = SelectObject(dc, prose as _);
        let mut y = top;

        for part in body.split('\n') {
            let part = part.trim_end();
            if let Some(command) = part.strip_prefix(COMMAND) {
                // The chip: mono, on its own line, boxed so the reader can see exactly what to type.
                SelectObject(dc, mono as _);
                let mut text = wide(command.trim());
                let mut extent = RECT { left: 0, top: 0, right: right - left, bottom: 0 };
                let flags = DT_LEFT | DT_TOP | DT_SINGLELINE | DT_NOPREFIX | DT_CALCRECT;
                let height = DrawTextW(dc, text.as_mut_ptr(), -1, &mut extent, flags);
                let (box_w, box_h) = (extent.right - extent.left + 20, height + 11);
                if paint {
                    let pen = CreatePen(PS_SOLID as _, 1, LINE);
                    let fill = CreateSolidBrush(SURFACE2);
                    let old_pen = SelectObject(dc, pen as _);
                    let old_fill = SelectObject(dc, fill as _);
                    RoundRect(dc, left, y, left + box_w, y + box_h, 8, 8);
                    SelectObject(dc, old_pen);
                    SelectObject(dc, old_fill);
                    DeleteObject(pen as _);
                    DeleteObject(fill as _);
                    SetTextColor(dc, TEXT_COLOUR);
                    let mut at = RECT {
                        left: left + 10,
                        top: y + 5,
                        right: left + box_w,
                        bottom: y + box_h,
                    };
                    DrawTextW(
                        dc,
                        text.as_mut_ptr(),
                        -1,
                        &mut at,
                        DT_LEFT | DT_TOP | DT_SINGLELINE | DT_NOPREFIX,
                    );
                }
                y += box_h;
                SelectObject(dc, prose as _);
            } else if part.is_empty() {
                y += GAP;
            } else {
                let mut text = wide(part);
                let mut at = RECT { left, top: y, right, bottom: y + 4_000 };
                let mut calc = at;
                let height = DrawTextW(
                    dc,
                    text.as_mut_ptr(),
                    -1,
                    &mut calc,
                    DT_LEFT | DT_TOP | DT_WORDBREAK | DT_NOPREFIX | DT_CALCRECT,
                );
                if paint {
                    SetTextColor(dc, MUTED);
                    DrawTextW(
                        dc,
                        text.as_mut_ptr(),
                        -1,
                        &mut at,
                        DT_LEFT | DT_TOP | DT_WORDBREAK | DT_NOPREFIX,
                    );
                }
                y += height;
            }
        }

        SelectObject(dc, previous);
        DeleteObject(prose as _);
        DeleteObject(mono as _);
        y - top
    }

    unsafe extern "system" fn overlay_proc(
        window: HWND,
        message: u32,
        wparam: WPARAM,
        lparam: LPARAM,
    ) -> LRESULT {
        match message {
            WM_PAINT => {
                let mut paint: PAINTSTRUCT = std::mem::zeroed();
                let dc = BeginPaint(window, &mut paint);
                let mut rect: RECT = std::mem::zeroed();
                GetClientRect(window, &mut rect);

                // Card, hairline, and the amber stripe that says which state this notice belongs to.
                let border = CreateSolidBrush(LINE);
                FillRect(dc, &rect, border);
                DeleteObject(border as _);
                let inner_card = RECT {
                    left: rect.left + 1,
                    top: rect.top + 1,
                    right: rect.right - 1,
                    bottom: rect.bottom - 1,
                };
                let card = CreateSolidBrush(SURFACE);
                FillRect(dc, &inner_card, card);
                DeleteObject(card as _);
                let stripe_rect = RECT {
                    left: rect.left + 1,
                    top: rect.top + 1,
                    right: rect.left + 1 + STRIPE,
                    bottom: rect.bottom - 1,
                };
                let stripe = CreateSolidBrush(LIVE);
                FillRect(dc, &stripe_rect, stripe);
                DeleteObject(stripe as _);

                SetBkMode(dc, TRANSPARENT as i32);
                let (mut title, body) = TEXT.with(|t| {
                    let (head, rest) = split(&t.borrow());
                    (head, String::from_utf16_lossy(&rest).trim_end_matches('\u{0}').to_string())
                });

                // The first line is the fact — which app, and why. It is set larger and brighter
                // because it is the only line a person reads while reaching for the mouse.
                let title_font = font(TITLE_PT, 650);
                let previous = SelectObject(dc, title_font as _);
                SetTextColor(dc, TEXT_COLOUR);
                let mut title_rect = RECT {
                    left: rect.left + STRIPE + PAD,
                    top: rect.top + PAD,
                    right: rect.right - PAD,
                    bottom: rect.bottom - PAD,
                };
                // Measured, then drawn. The height a `DrawTextW` returns is the height of what it
                // put on screen, which is one line short of what was wrapped when the string ends
                // without a break, and the body was landing on top of the title's last line.
                // DT_CALCRECT asks the same question of the same rect without painting anything.
                let mut calc = title_rect;
                let title_height = DrawTextW(
                    dc,
                    title.as_mut_ptr(),
                    -1,
                    &mut calc,
                    DT_LEFT | DT_TOP | DT_WORDBREAK | DT_NOPREFIX | DT_CALCRECT,
                );
                DrawTextW(
                    dc,
                    title.as_mut_ptr(),
                    -1,
                    &mut title_rect,
                    DT_LEFT | DT_TOP | DT_WORDBREAK | DT_NOPREFIX,
                );

                if !body.is_empty() {
                    draw_body(
                        dc,
                        title_rect.left,
                        rect.top + PAD + title_height + GAP,
                        title_rect.right,
                        &body,
                        true,
                    );
                }
                SelectObject(dc, previous);
                DeleteObject(title_font as _);

                EndPaint(window, &paint);
                0
            }
            // Clicking it dismisses it. There is nothing behind the click: the overlay explains, it
            // does not ask.
            WM_LBUTTONUP | WM_TIMER => {
                DestroyWindow(window);
                0
            }
            WM_DESTROY => {
                KillTimer(window, CLOSE_TIMER);
                0
            }
            _ => DefWindowProcW(window, message, wparam, lparam),
        }
    }

    /// How tall the card has to be to hold `text` without cutting a word off.
    ///
    /// The height used to be a constant, which was right while the only thing this window said was
    /// one sentence about a closed application. It now says everything the menu says, including a
    /// paragraph about why the service cannot be reached, and a fixed 196 px cut those in half. The
    /// measurement runs the same two fonts and the same wrap width as the paint pass, on a screen
    /// DC, and asks GDI where the text would end.
    fn measure(text: &str) -> i32 {
        let wrap = WIDTH - STRIPE - PAD * 2;
        let (mut title, body) = split(&wide(text));
        let body = String::from_utf16_lossy(&body).trim_end_matches('\u{0}').to_string();
        // SAFETY: a screen DC is released below, every object selected is deleted after the DC is
        // put back, and DT_CALCRECT draws nothing.
        unsafe {
            let dc = GetDC(std::ptr::null_mut());
            if dc.is_null() {
                return MIN_HEIGHT;
            }
            let mut rect = RECT { left: 0, top: 0, right: wrap, bottom: 0 };
            let title_font = font(TITLE_PT, 650);
            let previous = SelectObject(dc, title_font as _);
            let flags = DT_LEFT | DT_TOP | DT_WORDBREAK | DT_NOPREFIX | DT_CALCRECT;
            let title_height = DrawTextW(dc, title.as_mut_ptr(), -1, &mut rect, flags);
            let body_height =
                if body.is_empty() { 0 } else { GAP + draw_body(dc, 0, 0, wrap, &body, false) };
            SelectObject(dc, previous);
            DeleteObject(title_font as _);
            ReleaseDC(std::ptr::null_mut(), dc);

            let wanted = PAD * 2 + title_height + body_height;
            // Never taller than most of the screen: a card that runs off the bottom edge hides the
            // end of its own sentence and there is nothing to scroll.
            wanted.clamp(MIN_HEIGHT, (GetSystemMetrics(SM_CYSCREEN) * 3) / 4)
        }
    }

    /// Put the notice on screen. It never takes focus, so it cannot steal a keystroke from whatever
    /// the user moved on to.
    pub fn show(text: &str, dwell_ms: u32) {
        let height = measure(text);
        unsafe {
            let instance = GetModuleHandleW(std::ptr::null());
            let class_name = wide("CurfewOverlay");
            let mut class: WNDCLASSW = std::mem::zeroed();
            class.lpfnWndProc = Some(overlay_proc);
            class.hInstance = instance;
            class.lpszClassName = class_name.as_ptr();
            // Registering twice is harmless and returns zero; the second overlay reuses the class.
            RegisterClassW(&class);

            TEXT.with(|slot| *slot.borrow_mut() = wide(text));

            let screen_w = GetSystemMetrics(SM_CXSCREEN);
            let screen_h = GetSystemMetrics(SM_CYSCREEN);
            let window = CreateWindowExW(
                WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
                class_name.as_ptr(),
                class_name.as_ptr(),
                WS_POPUP,
                // Bottom right, where Windows puts everything else that speaks without being asked.
                // Centred, it landed on top of the window the user was about to go back to.
                screen_w - WIDTH - 24,
                screen_h - height - 72,
                WIDTH,
                height,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                instance,
                std::ptr::null(),
            );
            if window.is_null() {
                return;
            }
            // Rounded, like every other surface Windows 11 draws and like every card on the phone.
            let region = CreateRoundRectRgn(0, 0, WIDTH + 1, height + 1, 18, 18);
            SetWindowRgn(window, region, 0);

            ShowWindow(window, SW_SHOWNA);
            SetTimer(window, CLOSE_TIMER, dwell_ms, None);
        }
    }
}

#[cfg(not(windows))]
mod sys {
    pub fn show(_text: &str, _dwell_ms: u32) {}
}

pub use sys::show;

#[cfg(test)]
mod delay_tests {
    use super::*;
    use std::collections::BTreeMap;

    #[test]
    fn a_wait_is_described_as_a_pause_and_never_as_a_block() {
        let text = waiting_message("slack.exe", 15);
        assert!(text.contains("Slack opens in 15 seconds"));
        assert!(!text.to_lowercase().contains("blocked"));
        assert!(!text.contains("release"), "a delay has nothing to do with the 24-hour release");
    }

    #[test]
    fn the_last_second_does_not_read_as_a_countdown_of_one() {
        assert!(waiting_message("slack.exe", 1).contains("in a moment"));
        assert!(waiting_message("slack.exe", 0).contains("in a moment"));
    }

    #[test]
    fn a_wait_already_being_shown_is_not_announced_again_every_pass() {
        let current = BTreeMap::from([("slack.exe".to_string(), 12)]);
        let seen = BTreeSet::from(["slack.exe".to_string()]);
        assert!(newly_delayed(&seen, &current).is_empty());
        assert_eq!(newly_delayed(&BTreeSet::new(), &current), vec![("slack.exe".to_string(), 12)]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use curfew_core::{Lock, LockSet, Session, SessionSource};

    const NOW: Timestamp = 1_788_510_600;

    #[test]
    fn a_paragraph_is_given_longer_to_be_read_than_a_sentence() {
        let sentence = "Slack is blocked during deep-work.";
        let paragraph = crate::welcome::WELCOME;
        assert!(dwell_for(paragraph) > dwell_for(sentence));
        // The short case is not made slower by the change: it is still the seven seconds a closed
        // application has always had, plus a little.
        assert!(dwell_for(sentence) < DWELL_MS + 3_000);
    }

    #[test]
    fn a_runaway_string_cannot_park_a_card_on_the_screen() {
        let absurd = "x".repeat(100_000);
        assert_eq!(dwell_for(&absurd), 40_000);
    }

    fn set(names: &[&str]) -> BTreeSet<String> {
        names.iter().map(|n| n.to_string()).collect()
    }

    fn status(conditions: Vec<Lock>) -> Status {
        Status {
            now: NOW,
            running: vec![Session {
                id: "s1".into(),
                profile: "deep work".into(),
                source: SessionSource::Manual,
                started_at: NOW,
                lock: LockSet::new(conditions, Some(NOW + 3600)),
            }],
            ..Default::default()
        }
    }

    #[test]
    fn an_application_that_keeps_relaunching_is_explained_once() {
        let first = newly_closed(&set(&[]), &set(&["steam.exe"]));
        assert_eq!(first, vec!["steam.exe".to_string()]);

        // The next pass closes it again, and reports the same name.
        let again = newly_closed(&set(&["steam.exe"]), &set(&["steam.exe"]));
        assert!(again.is_empty(), "a relaunching app would produce a popup per pass");
    }

    #[test]
    fn a_second_application_is_explained_even_while_the_first_is_still_being_closed() {
        let newly = newly_closed(&set(&["steam.exe"]), &set(&["steam.exe", "discord.exe"]));
        assert_eq!(newly, vec!["discord.exe".to_string()]);
    }

    #[test]
    fn notices_are_rate_limited_however_many_names_appear() {
        assert!(should_show(&["steam.exe".into()], None, NOW));
        assert!(!should_show(&["steam.exe".into()], Some(NOW - 3), NOW));
        assert!(should_show(&["steam.exe".into()], Some(NOW - 30), NOW));
        assert!(!should_show(&[], None, NOW));
    }

    #[test]
    fn the_message_names_the_app_the_profile_and_the_end_time() {
        let text = message(&["steam.exe".into()], &status(vec![Lock::Timer]));
        assert!(text.contains("Steam"));
        assert!(text.contains("deep work"));
        assert!(text.contains("It ends at"));
    }

    #[test]
    fn a_locked_session_is_told_where_the_way_out_is() {
        let text = message(&["steam.exe".into()], &status(vec![Lock::DeviceCredential]));
        assert!(text.contains("24-hour release"));
    }

    #[test]
    fn a_timer_only_session_is_not_offered_a_way_out_it_does_not_have() {
        let text = message(&["steam.exe".into()], &status(vec![Lock::Timer]));
        assert!(!text.contains("24-hour release"));
    }

    #[test]
    fn several_apps_at_once_are_summarised_rather_than_listed_forever() {
        let closed: Vec<String> =
            ["a.exe", "b.exe", "c.exe", "d.exe"].iter().map(|s| s.to_string()).collect();
        let text = message(&closed, &status(vec![Lock::Timer]));
        assert!(text.contains("3 others"), "{text}");
    }

    #[test]
    fn a_close_with_no_session_running_does_not_claim_the_user_asked_for_it() {
        let text = message(&["steam.exe".into()], &Status { now: NOW, ..Default::default() });
        assert!(text.contains("was closed by Curfew"));
        assert!(!text.contains("during"));
    }
}
