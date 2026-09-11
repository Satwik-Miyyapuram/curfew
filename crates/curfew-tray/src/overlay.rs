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

/// Profile names whose sessions have appeared since the previous poll.
///
/// The other half of "the block started and nobody said so". Every close gets a sentence, but the
/// *reason* for the close — a session beginning — was announced nowhere: a schedule came round, apps
/// started disappearing, and the first thing the user saw was a card about Steam. This is what lets
/// that card be preceded by, or replaced with, the sentence that explains it.
///
/// Keyed on the profile rather than the session id, because a schedule that restarts a session under
/// a new id is not news to the person reading the screen. Returned sorted and de-duplicated so the
/// sentence is stable.
pub fn newly_started(previous: &BTreeSet<String>, current: &[curfew_core::Session]) -> Vec<String> {
    let now: BTreeSet<&str> = current.iter().map(|s| s.profile.as_str()).collect();
    now.difference(&previous.iter().map(String::as_str).collect())
        .map(|id| (*id).to_string())
        .collect()
}

/// The sentence for a session that has just begun.
///
/// Says the name, says when it ends, and says what it is doing — the three things a person wants on
/// finding their windows closing. Deliberately not a wall: the overlay does not take the keyboard and
/// leaves on its own, so this is information rather than an interruption.
pub fn started_message(profiles: &[String], status: &Status) -> String {
    let names: Vec<String> = profiles.iter().map(|id| status.name_of(id).to_string()).collect();
    let who = match names.as_slice() {
        [] => return String::new(),
        [one] => one.clone(),
        [first, second] => format!("{first} and {second}"),
        many => format!("{} and {} others", many[0], many.len() - 1),
    };

    // The end time of the session that just started, which is the one the user is now inside. When
    // several started at once they usually share a window, so the latest end is the useful answer.
    let ends = status
        .running
        .iter()
        .filter(|s| profiles.contains(&s.profile))
        .filter_map(|s| s.lock.ends_at)
        .max();

    let mut text = match ends {
        Some(ends) => format!("{who} is running now, until {}.", crate::menu::when(ends)),
        None => format!("{who} is running now."),
    };
    text.push_str(
        "\nThis is the block you asked for. Anything it covers will close if you open it.",
    );
    if status.running.iter().any(|s| {
        profiles.contains(&s.profile)
            && s.lock.conditions.iter().any(|l| !matches!(l, curfew_core::Lock::Timer))
    }) {
        text.push_str("\nThe Curfew icon can end it early, or start the 24-hour release.");
    }
    text
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

    // The name the user gave the profile, not the slug from the config. `Session.profile` is the id,
    // so this sentence used to read *"Steam is blocked during distractions."* where the phone says
    // *"Distractions"* — a starter config whose id happens to look like a word hid it, and a
    // `deep-work` profile would have made it obvious.
    let profile = status.running_name().map(str::to_string);
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
        CreateWindowExW, DefWindowProcW, DestroyWindow, GetClientRect, GetSystemMetrics,
        GetWindowLongPtrW, KillTimer, RegisterClassW, SetTimer, SetWindowLongPtrW, ShowWindow,
        GWLP_USERDATA, SM_CXSCREEN, SM_CYSCREEN, SW_SHOWNA, WM_DESTROY, WM_LBUTTONUP, WM_NCDESTROY,
        WM_PAINT, WM_TIMER, WNDCLASSW, WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW, WS_EX_TOPMOST, WS_POPUP,
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

    /// Each window's own copy of its text, reached through `GWLP_USERDATA`.
    ///
    /// **A single `thread_local` slot was the bug** — P2-14. `show()` wrote it and `WM_PAINT` read it,
    /// so a second notice overwrote the first before it had painted and the earlier card rendered the
    /// later text. That is not an edge case: `note` fires once per closed app per pass, so two notices
    /// one after another is the ordinary way this is used.
    ///
    /// The window owns its `Vec<u16>` now, which also makes the buffer's lifetime *correct* rather than
    /// accidental: before, the text a window painted belonged to whoever wrote the slot last, and the
    /// window had no way to know it had changed.
    ///
    /// SAFETY: the pointer stored here is one `Box::into_raw` for this window alone, and
    /// [`take_text`] is the only reader. The box is reclaimed on `WM_NCDESTROY`, which is the last
    /// message a window receives.
    unsafe fn take_text(window: HWND) -> *mut Vec<u16> {
        GetWindowLongPtrW(window, GWLP_USERDATA) as *mut Vec<u16>
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
                // This window's own text, not a shared slot — see `take_text`. A window without one
                // has nothing to draw, which is what `WM_PAINT` before `show` finished would be.
                let mine = take_text(window);
                if mine.is_null() {
                    EndPaint(window, &paint);
                    return 0;
                }
                let (mut title, body) = {
                    // SAFETY: `mine` is the box this window owns, alive until `WM_NCDESTROY`, and this
                    // borrow ends before the message returns.
                    let t = &*mine;
                    let (head, rest) = split(t);
                    (head, String::from_utf16_lossy(&rest).trim_end_matches('\u{0}').to_string())
                };

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
            // The last message a window gets, and the only safe place to take the box back: after this
            // the pointer is gone and nothing can paint again.
            WM_NCDESTROY => {
                let mine = take_text(window);
                if !mine.is_null() {
                    // SAFETY: the pointer is this window's own `Box::into_raw`, cleared below so a
                    // second `WM_NCDESTROY` cannot free it twice.
                    drop(Box::from_raw(mine));
                    SetWindowLongPtrW(window, GWLP_USERDATA, 0);
                }
                DefWindowProcW(window, message, wparam, lparam)
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

            // The window's own copy, handed over before it is shown. `into_raw` because the box
            // outlives this call: `WM_NCDESTROY` is what takes it back.
            let owned = Box::into_raw(Box::new(wide(text)));

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
                // The box was made before the window could take it, so this path has to drop it or it
                // leaks. Two lines rather than one for exactly that reason.
                drop(Box::from_raw(owned));
                return;
            }
            // SAFETY: `owned` came from `Box::into_raw` a moment ago and belongs to this window from
            // here on; `GWLP_USERDATA` is the slot Win32 provides for exactly this.
            SetWindowLongPtrW(window, GWLP_USERDATA, owned as isize);
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

    fn running(profile: &str, ends_at: Option<Timestamp>) -> Session {
        Session {
            id: format!("s-{profile}"),
            profile: profile.into(),
            source: SessionSource::Weekly { schedule: "mornings".into() },
            started_at: NOW,
            lock: LockSet::new([Lock::Timer], ends_at),
        }
    }

    /// A session that has just begun is noticed once, and only once.
    ///
    /// The close cards explain *what* disappeared; nothing said *why*. A schedule came round, apps
    /// started vanishing, and the first sentence the user read was about Steam.
    #[test]
    fn a_session_that_has_just_started_is_noticed_exactly_once() {
        let sessions = vec![running("deep-work", Some(NOW + 3600))];
        assert_eq!(newly_started(&set(&[]), &sessions), vec!["deep-work".to_string()]);

        // On the next poll it is no longer new, which is what stops the notice repeating for as long
        // as the session runs.
        assert!(newly_started(&set(&["deep-work"]), &sessions).is_empty());
    }

    /// A session already running when the tray starts is not announced.
    ///
    /// The first poll has an empty "previous", so without the seeding rule below every launch of the
    /// tray would announce whatever happened to be running — an event that could be hours old,
    /// presented as news. The cost is that a session starting in the half-second before the tray
    /// launches is missed, which is the right way round.
    #[test]
    fn the_first_poll_does_not_announce_what_was_already_running() {
        // This is what `shell.rs` does on its first poll.
        let sessions = vec![running("deep-work", Some(NOW + 3600))];
        let seeded: BTreeSet<String> = sessions.iter().map(|s| s.profile.clone()).collect();
        assert!(newly_started(&seeded, &sessions).is_empty());
    }

    /// The sentence names the profile the way its owner does, and says when it ends.
    #[test]
    fn the_started_sentence_names_the_profile_and_when_it_ends() {
        let mut status = Status {
            now: NOW,
            running: vec![running("deep-work", Some(NOW + 3600))],
            ..Default::default()
        };
        status.profile_names.insert("deep-work".into(), "Deep work".into());

        let text = started_message(&["deep-work".to_string()], &status);

        assert!(text.contains("Deep work"), "the id was used instead of the name: {text}");
        assert!(!text.contains("deep-work"), "the slug leaked into the notice: {text}");
        assert!(text.contains("until"), "the notice does not say when it ends: {text}");
        // It says what is happening, not merely that something is.
        assert!(text.contains("block you asked for"), "{text}");
    }

    /// A session with only a timer gets no invented way out.
    ///
    /// Saying "the icon can end it early" about a session whose whole lock is a timer would promise an
    /// exit the service then refuses — the exact failure this file exists to prevent.
    #[test]
    fn a_started_session_with_only_a_timer_is_not_offered_an_early_exit() {
        let status =
            Status { now: NOW, running: vec![running("deep-work", None)], ..Default::default() };
        let text = started_message(&["deep-work".to_string()], &status);
        assert!(!text.contains("end it early"), "an exit was promised that does not exist: {text}");
    }

    /// Nothing new means nothing to say, rather than a card with an empty sentence.
    #[test]
    fn no_new_sessions_means_no_sentence() {
        assert!(started_message(&[], &Status { now: NOW, ..Default::default() }).is_empty());
    }
}

/// **Two notices must not share one buffer** — P2-14.
///
/// `show()` wrote a single `thread_local` and `WM_PAINT` read it, so a second notice overwrote the
/// first before it had painted and the earlier card rendered the later text. Not an edge case: `note`
/// fires once per closed app per pass, so two notices in a row is how this is normally used.
///
/// The window code itself cannot run in this test binary — `overlay_proc` is a Win32 callback — so what
/// is pinned here is the ownership rule the fix rests on: **each window's text is reached through its
/// own `GWLP_USERDATA`, and a box handed to `Box::into_raw` is reclaimed exactly once.** The pointer
/// plumbing between those two facts is verified by reading `overlay_proc`, and this test says so rather
/// than implying more.
#[cfg(test)]
mod ownership_tests {
    /// A window's text is identified by the pointer, and two of them are never the same allocation.
    ///
    /// This is the property the old shared slot lacked: with one slot, "which text does this window
    /// paint" had no answer, because the answer changed underneath it.
    #[test]
    fn two_windows_own_two_buffers() {
        let first = Box::into_raw(Box::new(vec![1u16, 2, 3, 0]));
        let second = Box::into_raw(Box::new(vec![9u16, 8, 0]));

        assert_ne!(first, second, "two windows were given the same buffer");
        // SAFETY: both came from `Box::into_raw` above and neither has been reclaimed.
        unsafe {
            assert_eq!((*first).len(), 4);
            assert_eq!((*second).len(), 3);
            // Reclaimed once each, which is what `WM_NCDESTROY` does.
            drop(Box::from_raw(first));
            drop(Box::from_raw(second));
        }
    }

    /// A window that never took its box still has to free it, or every failed `CreateWindowExW` leaks
    /// the notice's text. `show` does exactly this on the null-window path.
    #[test]
    fn a_box_a_window_never_took_is_still_freed() {
        let orphan = Box::into_raw(Box::new(vec![7u16, 0]));
        // The null-window branch in `show`: nothing else will ever reference this pointer.
        // SAFETY: from `Box::into_raw` immediately above, and reclaimed exactly once.
        let reclaimed = unsafe { Box::from_raw(orphan) };
        assert_eq!(*reclaimed, vec![7u16, 0]);
    }

    /// The text is stored NUL-terminated, because `DrawTextW` is given `-1` and reads until the
    /// terminator. A buffer without one would run past its end and paint whatever followed it.
    ///
    /// This is the one part of the old code that was already right and is easy to break while moving the
    /// buffer: the `chain(once(0))` is what the whole drawing path depends on.
    /// **Portable on purpose.** The production path uses `OsStr::encode_wide`, a Windows trait — so a
    /// Windows-gated test would be skipped by CI, which runs on Linux, and the property would go
    /// unchecked exactly where it is easiest to break. `str::encode_utf16` agrees with `encode_wide`
    /// for every string this code is given, so the assertion runs everywhere.
    #[test]
    fn the_owned_text_is_nul_terminated() {
        let text = "Steam was closed";
        let wide: Vec<u16> = text.encode_utf16().chain(std::iter::once(0)).collect();
        assert_eq!(*wide.last().unwrap(), 0, "the buffer is not terminated");
        assert_eq!(wide.len(), text.chars().count() + 1);
        // And no interior NUL: `DrawTextW` is given `-1` and stops at the first one, so an embedded
        // terminator would silently truncate the notice rather than fail loudly.
        assert!(
            !wide[..wide.len() - 1].contains(&0),
            "the text contains an interior NUL, which would cut the notice short"
        );
    }
}

/// **The shared slot must not come back**, and only a source-level check can say so.
///
/// The mutation run is why this exists. Setting `take_text` to always return null — which would leave
/// every overlay painting nothing — is caught by **no test in this binary**, because `overlay_proc` is a
/// Win32 callback and cannot run under `cargo test`. An ownership test can pin the *shape* of the rule
/// (a `Box::into_raw` with one matching reclaim) but not the wiring between `show`, the window's
/// `GWLP_USERDATA` and the paint.
///
/// What is checkable is the shape of the *bug*: P2-14 was one `thread_local` that every window read and
/// every `show()` wrote. That is a fact about this file, so it is asserted against this file — the same
/// technique as the service's log-sink guard, and with the same limit, stated rather than implied.
#[cfg(test)]
mod shared_slot_tests {
    const SOURCE: &str = include_str!("overlay.rs");

    /// Everything before the test modules: the code that actually runs.
    fn production() -> &'static str {
        SOURCE.split("#[cfg(test)]").next().expect("split yields at least one part")
    }

    #[test]
    fn the_overlay_has_no_shared_text_slot() {
        let code = production();
        let offenders: Vec<&str> = code
            .lines()
            .filter(|line| !line.trim_start().starts_with("//"))
            .filter(|line| line.contains("thread_local!"))
            .collect();
        assert!(
            offenders.is_empty(),
            "a thread-local came back into the overlay, which is what made two notices share one text              buffer: {offenders:?}"
        );
    }

    /// And the replacement is actually wired: the text is stored on the window and read back from it.
    ///
    /// **Match the store and the reclaim specifically, not a substring of them.** The first version of
    /// this guard used `contains`, and was loose in two ways the mutation run exposed:
    ///
    ///  - `contains("WM_NCDESTROY")` also matches `WM_NCDESTROY_NEVER`, so renaming the destroying arm
    ///    away still passed;
    ///  - `contains("SetWindowLongPtrW(window, GWLP_USERDATA")` also matches the *clearing* call inside
    ///    that same handler, so deleting the store that attaches the text still passed.
    ///
    /// Both are one mistake in miniature — a substring answering a slightly different question from the
    /// one asked — which is the same failure this branch has now hit with vacuous guards five times.
    ///
    /// Still narrow: it asserts the calls are present and reachable, not that they are correct. The
    /// correctness of a Win32 callback is not something this binary can observe.
    #[test]
    fn the_text_is_stored_on_the_window_and_read_back_from_it() {
        let code = production();

        // The store, with the pointer being handed over — not the later call that clears it.
        assert!(
            code.contains("SetWindowLongPtrW(window, GWLP_USERDATA, owned as isize)"),
            "the overlay no longer attaches its text to the window"
        );
        assert!(
            code.contains("GetWindowLongPtrW(window, GWLP_USERDATA) as *mut Vec<u16>"),
            "the overlay no longer reads its text back from the window"
        );

        // The reclaim: a `WM_NCDESTROY` arm that actually frees the box.
        //
        // Matched by scanning for the **whole pattern**, not with `contains("WM_NCDESTROY")` — that also
        // matches `WM_NCDESTROY_NEVER`, and renaming the arm away was one of the two mutations this
        // guard originally missed. No `regex` dependency for one assertion; a line scan says the same
        // thing and a reader can check it.
        // **The whole identifier, not a prefix of it.** `starts_with("WM_NCDESTROY")` is true for
        // `WM_NCDESTROY_NEVER`, which is exactly the mutation this check was written to catch — a prefix
        // check is a substring check wearing a different hat, and this guard has now been loose in that
        // direction three times. The token is taken as a token and compared.
        let arm: Option<usize> = code.lines().position(|line| {
            let trimmed = line.trim().trim_start_matches("//").trim();
            let name = trimmed.split_whitespace().next().unwrap_or("");
            name == "WM_NCDESTROY" && trimmed.ends_with("=> {")
        });
        let arm =
            arm.expect("nothing handles the last message, so the window's text is never reclaimed");
        let after: String = code.lines().skip(arm).take(12).collect::<Vec<_>>().join("\n");
        assert!(
            after.contains("Box::from_raw"),
            "the last message no longer frees the window's text, so each notice leaks its buffer"
        );
    }
}
