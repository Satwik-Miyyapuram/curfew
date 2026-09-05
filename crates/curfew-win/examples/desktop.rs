//! What Curfew can see of this machine right now. A hand-run smoke test for the Win32 layer, which
//! is the one part of the crate no automated test can cover.
fn main() {
    let titles = curfew_win::windows::window_titles();
    println!("{} visible windows", titles.len());
    for (pid, title) in titles.iter().take(10) {
        println!("  {pid:>6}  {title}");
    }
    println!("foreground: {:?}", curfew_win::windows::foreground());
}
