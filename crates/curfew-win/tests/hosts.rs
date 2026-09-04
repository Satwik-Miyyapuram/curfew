//! The hosts file belongs to the whole machine, not to Curfew.
//!
//! Every test here is really the same test: whatever Curfew writes, everything it did not write
//! must survive untouched, and Curfew's own block must always be removable. A blocker that corrupts
//! name resolution has done far more damage than the distraction it was stopping.

use curfew_win::hosts::{apply, clear, render, strip, BEGIN, END};
use std::collections::BTreeSet;

fn domains(list: &[&str]) -> BTreeSet<String> {
    list.iter().map(|d| d.to_string()).collect()
}

const EXISTING: &str = "127.0.0.1 localhost\r\n::1 localhost\r\n0.0.0.0 ads.example\r\n";

#[test]
fn what_was_already_there_is_kept() {
    let out = render(EXISTING, &domains(&["reddit.com"]));
    assert!(out.contains("127.0.0.1 localhost"));
    assert!(out.contains("::1 localhost"));
    assert!(out.contains("0.0.0.0 ads.example"), "another blocker's entry was eaten");
}

#[test]
fn a_domain_is_blocked_with_its_www_form() {
    let out = render(EXISTING, &domains(&["reddit.com"]));
    assert!(out.contains("0.0.0.0 reddit.com\r\n"));
    assert!(out.contains("0.0.0.0 www.reddit.com\r\n"));
}

#[test]
fn a_www_domain_is_not_doubled_up() {
    let out = render("", &domains(&["www.reddit.com"]));
    assert_eq!(out.matches("www.reddit.com").count(), 1);
    assert!(!out.contains("www.www."));
}

#[test]
fn a_pasted_url_still_produces_a_usable_line() {
    let out = render("", &domains(&["https://www.Reddit.com/r/rust?x=1"]));
    assert!(out.contains("0.0.0.0 www.reddit.com\r\n"), "got: {out}");
    assert!(!out.contains("https"));
    assert!(!out.contains('/'));
}

#[test]
fn rewriting_is_idempotent() {
    let once = render(EXISTING, &domains(&["reddit.com", "x.com"]));
    let twice = render(&once, &domains(&["reddit.com", "x.com"]));
    assert_eq!(once, twice, "each pass must replace the block, not stack another one");
}

#[test]
fn removing_a_domain_removes_only_that_domain() {
    let both = render(EXISTING, &domains(&["reddit.com", "x.com"]));
    let one = render(&both, &domains(&["x.com"]));
    assert!(!one.contains("reddit.com"));
    assert!(one.contains("0.0.0.0 x.com\r\n"));
    assert!(one.contains("0.0.0.0 ads.example"));
}

#[test]
fn an_empty_block_leaves_the_file_as_it_was() {
    let blocked = render(EXISTING, &domains(&["reddit.com"]));
    assert_eq!(render(&blocked, &BTreeSet::new()), EXISTING);
}

#[test]
fn a_block_left_unterminated_by_a_crash_can_still_be_removed() {
    // No END marker: what a power cut mid-write leaves behind. Without tolerance here the machine
    // would have a block nothing could ever lift.
    let broken = format!("127.0.0.1 localhost\r\n{BEGIN}\r\n0.0.0.0 reddit.com\r\n");
    let out = strip(&broken);
    assert_eq!(out, "127.0.0.1 localhost\r\n");
}

#[test]
fn markers_the_user_typed_by_hand_are_still_honoured() {
    let handmade =
        format!("  {BEGIN}  \r\n0.0.0.0 reddit.com\r\n  {END}  \r\n127.0.0.1 localhost\r\n");
    assert_eq!(strip(&handmade), "127.0.0.1 localhost\r\n");
}

#[test]
fn a_file_with_no_trailing_newline_does_not_glue_onto_our_block() {
    let out = render("127.0.0.1 localhost", &domains(&["reddit.com"]));
    assert!(out.contains("127.0.0.1 localhost\r\n"), "got: {out:?}");
    assert!(!out.contains("localhost#"), "got: {out:?}");
}

#[test]
fn blank_and_comment_lines_survive() {
    let commented = "# my notes\r\n\r\n127.0.0.1 localhost\r\n";
    let out = render(commented, &domains(&["reddit.com"]));
    assert!(out.starts_with("# my notes\r\n\r\n127.0.0.1 localhost\r\n"));
}

#[test]
fn writing_and_clearing_round_trips_on_disk() {
    let dir = std::env::temp_dir().join(format!("curfew-hosts-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("hosts");
    std::fs::write(&path, EXISTING).unwrap();

    apply(&path, &domains(&["reddit.com"])).unwrap();
    let written = std::fs::read_to_string(&path).unwrap();
    assert!(written.contains("0.0.0.0 reddit.com"));

    clear(&path).unwrap();
    assert_eq!(std::fs::read_to_string(&path).unwrap(), EXISTING);

    // The temporary file the atomic write goes through must not be left behind: the etc directory
    // is one people read to find out what is blocking them.
    assert!(!dir.join("hosts.curfew.tmp").exists());
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn a_missing_hosts_file_is_created_rather_than_erroring() {
    let dir = std::env::temp_dir().join(format!("curfew-hosts-missing-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("hosts");

    apply(&path, &domains(&["reddit.com"])).unwrap();

    assert!(std::fs::read_to_string(&path).unwrap().contains("0.0.0.0 reddit.com"));
    std::fs::remove_dir_all(&dir).unwrap();
}
