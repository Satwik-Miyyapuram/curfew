//! A resolver that refuses to look up what is blocked.
//!
//! The hosts file is exact-match and nothing else: `reddit.com` in it does not stop
//! `old.reddit.com`, `www.reddit.com`, or the next subdomain someone posts a workaround for. Writing
//! every subdomain into it is not an answer, because the list is unbounded and the file is read by
//! everything on the machine.
//!
//! So Curfew also answers DNS itself, on loopback, and the hosts file stays as the floor underneath:
//! if this proxy is not running, or the interface's DNS has been pointed elsewhere, the exact names
//! are still blocked. Two weak mechanisms that fail independently beat one that fails silently.
//!
//! What is here is the part that can be wrong in interesting ways — reading a name out of a packet
//! and deciding about it — kept apart from the socket so it can be tested exhaustively. A resolver
//! that mis-parses a packet is a resolver that breaks someone's internet, which is the fastest way
//! to have a blocker uninstalled.

use std::collections::BTreeSet;

/// The largest DNS message this will look at. Anything longer is forwarded without inspection: a
/// query that does not fit is not a query anyone is browsing with.
pub const MAX_MESSAGE: usize = 512;

/// The address the proxy listens on. Loopback only — a resolver reachable from the network would be
/// an open resolver, which is somebody else's outage waiting to happen.
pub const LISTEN: &str = "127.0.0.1:53";

/// How long a blocked answer claims to be good for. Deliberately short: when a block ends, the
/// browser must not keep a refusal cached for the rest of the afternoon.
pub const BLOCK_TTL: u32 = 5;

/// What to do with one incoming message.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Answer {
    /// Reply with an address that goes nowhere.
    Refuse { name: String },
    /// Pass it upstream untouched.
    Forward,
}

/// Read the question's name out of a query.
///
/// Returns `None` for anything that is not a single well-formed question — a response, a malformed
/// packet, a compression pointer (which cannot appear in a question anyway and is the classic way to
/// walk a parser off the end of a buffer), or a name that runs past the end of the message. Every
/// one of those is forwarded rather than guessed at.
pub fn question(message: &[u8]) -> Option<String> {
    if message.len() < 12 {
        return None;
    }
    // A response, not a query: bit 15 of the flags.
    if message[2] & 0x80 != 0 {
        return None;
    }
    // Exactly one question. Zero is nothing to decide about; more than one is so rare in practice
    // that answering it would be more risk than the feature is worth.
    if u16::from_be_bytes([message[4], message[5]]) != 1 {
        return None;
    }

    let mut at = 12;
    let mut labels: Vec<String> = Vec::new();
    loop {
        let length = *message.get(at)? as usize;
        if length == 0 {
            break;
        }
        // Top two bits set means a pointer. Not legal in a question, and following one is how a
        // parser is talked into an infinite loop.
        if length & 0xc0 != 0 {
            return None;
        }
        let start = at + 1;
        let end = start + length;
        let label = message.get(start..end)?;
        labels.push(String::from_utf8(label.to_vec()).ok()?.to_lowercase());
        at = end;
    }

    if labels.is_empty() {
        return None;
    }
    Some(labels.join("."))
}

/// Whether `name` is covered by a blocked domain.
///
/// A blocked `reddit.com` covers `old.reddit.com` and `i.redd.it` is not covered by it — matching is
/// on label boundaries only, so blocking `red.com` never takes down `notred.com`, and blocking
/// `example.com` never takes down `example.company`.
pub fn covered(name: &str, blocked: &BTreeSet<String>) -> Option<String> {
    let name = name.to_lowercase();
    blocked
        .iter()
        .find(|domain| {
            let domain = domain.trim_start_matches("www.");
            name == domain || name.ends_with(&format!(".{domain}"))
        })
        .cloned()
}

/// The decision for one message.
pub fn answer(message: &[u8], blocked: &BTreeSet<String>) -> Answer {
    if message.len() > MAX_MESSAGE {
        return Answer::Forward;
    }
    match question(message).and_then(|name| covered(&name, blocked)) {
        Some(name) => Answer::Refuse { name },
        None => Answer::Forward,
    }
}

/// Build the refusal for a query.
///
/// `0.0.0.0` rather than NXDOMAIN, and rather than a page of our own: NXDOMAIN makes some clients
/// retry against a hard-coded resolver, and serving a real page from a blocker means holding a
/// certificate for someone else's domain, which is a thing Curfew is never going to do.
pub fn refusal(query: &[u8]) -> Vec<u8> {
    let mut reply = query.to_vec();
    // QR = response, RA = recursion available, RCODE = 0. The rest of the flags in byte 2 (opcode,
    // and the client's RD bit) are the client's own and are echoed back as they arrived.
    reply[2] |= 0x80;
    reply[3] = 0x80;

    let qtype_at = 12 + name_length(query);
    let qtype = query
        .get(qtype_at..qtype_at + 2)
        .map(|b| u16::from_be_bytes([b[0], b[1]]))
        .unwrap_or(0);

    // Only an A question gets an address. AAAA and everything else get an empty, successful answer,
    // which is how a resolver says "nothing here" without pretending a name does not exist — and
    // without handing out an IPv6 address that would be tried first and time out slowly.
    let answers: u16 = if qtype == 1 { 1 } else { 0 };
    reply[6..8].copy_from_slice(&answers.to_be_bytes());
    reply[8..10].copy_from_slice(&0u16.to_be_bytes());
    reply[10..12].copy_from_slice(&0u16.to_be_bytes());
    reply.truncate(qtype_at + 4);

    if answers == 1 {
        reply.extend_from_slice(&[0xc0, 0x0c]); // the question's name, by pointer
        reply.extend_from_slice(&1u16.to_be_bytes()); // A
        reply.extend_from_slice(&1u16.to_be_bytes()); // IN
        reply.extend_from_slice(&BLOCK_TTL.to_be_bytes());
        reply.extend_from_slice(&4u16.to_be_bytes());
        reply.extend_from_slice(&[0, 0, 0, 0]);
    }
    reply
}

/// The encoded length of the question's name, including the root label.
fn name_length(message: &[u8]) -> usize {
    let mut at = 12;
    while let Some(&length) = message.get(at) {
        at += 1 + length as usize;
        if length == 0 {
            break;
        }
    }
    at - 12
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A query for `name`, of type `qtype`, as a client would send it.
    fn query(name: &str, qtype: u16) -> Vec<u8> {
        let mut message = vec![0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0];
        for label in name.split('.') {
            message.push(label.len() as u8);
            message.extend_from_slice(label.as_bytes());
        }
        message.push(0);
        message.extend_from_slice(&qtype.to_be_bytes());
        message.extend_from_slice(&1u16.to_be_bytes());
        message
    }

    fn blocked() -> BTreeSet<String> {
        ["reddit.com".to_string(), "www.youtube.com".to_string()].into_iter().collect()
    }

    #[test]
    fn a_question_is_read_back_as_the_name_that_was_asked_for() {
        assert_eq!(question(&query("old.reddit.com", 1)).as_deref(), Some("old.reddit.com"));
    }

    #[test]
    fn a_name_is_matched_regardless_of_the_case_it_was_asked_in() {
        assert_eq!(question(&query("OLD.Reddit.COM", 1)).as_deref(), Some("old.reddit.com"));
    }

    #[test]
    fn a_subdomain_of_a_blocked_domain_is_refused_which_is_the_whole_point_of_this() {
        // The hosts file cannot do this, and every workaround thread starts with `old.reddit.com`.
        assert_eq!(
            answer(&query("old.reddit.com", 1), &blocked()),
            Answer::Refuse { name: "reddit.com".into() }
        );
    }

    #[test]
    fn a_name_that_merely_ends_in_the_same_letters_is_left_alone() {
        assert_eq!(answer(&query("notreddit.com", 1), &blocked()), Answer::Forward);
        assert_eq!(answer(&query("reddit.command.org", 1), &blocked()), Answer::Forward);
    }

    #[test]
    fn a_www_prefix_in_the_block_list_still_covers_the_bare_domain() {
        assert_eq!(
            answer(&query("youtube.com", 1), &blocked()),
            Answer::Refuse { name: "www.youtube.com".into() }
        );
    }

    #[test]
    fn everything_not_blocked_is_forwarded_untouched() {
        assert_eq!(answer(&query("docs.rs", 1), &blocked()), Answer::Forward);
    }

    #[test]
    fn a_malformed_packet_is_forwarded_rather_than_guessed_at() {
        // Breaking somebody's internet is a worse failure than missing one block, and both of these
        // are what a fuzzer sends first.
        assert_eq!(answer(&[], &blocked()), Answer::Forward);
        assert_eq!(answer(&[0; 11], &blocked()), Answer::Forward);
        let mut truncated = query("old.reddit.com", 1);
        truncated.truncate(15);
        assert_eq!(answer(&truncated, &blocked()), Answer::Forward);
    }

    #[test]
    fn a_compression_pointer_in_a_question_is_refused_rather_than_followed() {
        // Illegal there, and following one is the classic way to walk a parser into a loop.
        let mut message = vec![0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0];
        message.extend_from_slice(&[0xc0, 0x0c, 0, 1, 0, 1]);
        assert_eq!(question(&message), None);
        assert_eq!(answer(&message, &blocked()), Answer::Forward);
    }

    #[test]
    fn a_response_arriving_on_the_listening_socket_is_never_answered_again() {
        let mut message = query("old.reddit.com", 1);
        message[2] |= 0x80;
        assert_eq!(answer(&message, &blocked()), Answer::Forward);
    }

    #[test]
    fn a_query_with_no_question_or_several_is_forwarded() {
        let mut none = query("old.reddit.com", 1);
        none[4..6].copy_from_slice(&0u16.to_be_bytes());
        assert_eq!(answer(&none, &blocked()), Answer::Forward);

        let mut two = query("old.reddit.com", 1);
        two[4..6].copy_from_slice(&2u16.to_be_bytes());
        assert_eq!(answer(&two, &blocked()), Answer::Forward);
    }

    #[test]
    fn an_oversized_message_is_forwarded_without_being_read() {
        let mut huge = query("old.reddit.com", 1);
        huge.resize(MAX_MESSAGE + 1, 0);
        assert_eq!(answer(&huge, &blocked()), Answer::Forward);
    }

    #[test]
    fn nothing_is_refused_when_nothing_is_blocked() {
        assert_eq!(answer(&query("old.reddit.com", 1), &BTreeSet::new()), Answer::Forward);
    }

    // --- what a refusal looks like on the wire -------------------------------------------------

    #[test]
    fn a_refusal_is_a_successful_answer_pointing_at_nowhere() {
        let query = query("old.reddit.com", 1);
        let reply = refusal(&query);

        assert_eq!(reply[0..2], query[0..2], "the transaction id must come back unchanged");
        assert_eq!(reply[2] & 0x80, 0x80, "not marked as a response");
        assert_eq!(reply[3] & 0x0f, 0, "an error code would make some clients try another resolver");
        assert_eq!(u16::from_be_bytes([reply[6], reply[7]]), 1);
        assert_eq!(reply[reply.len() - 4..], [0, 0, 0, 0]);
        assert_eq!(u32::from_be_bytes(reply[reply.len() - 10..reply.len() - 6].try_into().unwrap()), BLOCK_TTL);
    }

    #[test]
    fn the_recursion_desired_bit_the_client_set_is_echoed_rather_than_invented() {
        let mut query = query("old.reddit.com", 1);
        query[2] = 0x00; // a client that did not ask for recursion
        assert_eq!(refusal(&query)[2] & 0x01, 0);
    }

    #[test]
    fn a_non_address_question_gets_an_empty_answer_rather_than_a_fake_address() {
        // AAAA in particular: handing back an address here would be tried first and time out slowly,
        // which looks like a broken network rather than a block.
        let reply = refusal(&query("old.reddit.com", 28));
        assert_eq!(u16::from_be_bytes([reply[6], reply[7]]), 0);
        assert_eq!(reply[3] & 0x0f, 0, "NXDOMAIN would send some clients to a hard-coded resolver");
    }

    #[test]
    fn the_answer_never_claims_a_long_life_that_would_outlast_the_block() {
        let reply = refusal(&query("old.reddit.com", 1));
        let ttl = u32::from_be_bytes(reply[reply.len() - 10..reply.len() - 6].try_into().unwrap());
        assert!(ttl <= 60, "a cached refusal must not outlive the session that caused it");
    }
}

// --- the socket half ------------------------------------------------------------------------

use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

/// How long to wait for the upstream resolver before giving up on one query. A client that gets no
/// answer retries; a client left hanging looks like a dead network.
const UPSTREAM_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(3);

/// The most queries being forwarded at once. Past this, new ones are dropped rather than answered,
/// because a resolver that spawns a thread per packet is a resolver that anything on the machine can
/// turn into a fork bomb.
const MAX_IN_FLIGHT: usize = 64;

/// The running proxy. Dropping it stops the listener.
pub struct Proxy {
    blocked: Arc<Mutex<BTreeSet<String>>>,
    stop: Arc<AtomicBool>,
    /// The address actually bound, which is not `LISTEN` when the tests ask for port 0.
    pub address: SocketAddr,
    /// What the machine's interfaces were using before Curfew took them over, so they can be given
    /// back exactly. Empty until [`Proxy::take_over`] is called.
    previous: Vec<Interface>,
    /// Where `previous` is also written, so an uninstall running in another process can give the
    /// resolvers back even if this one was killed rather than stopped.
    remembered: Option<std::path::PathBuf>,
}

impl Proxy {
    /// Bind and start answering. `upstream` is where everything not blocked is sent.
    ///
    /// Binding fails when something else already holds the port — another blocker, a local resolver,
    /// or a previous Curfew that has not let go. That is reported rather than retried in a loop: the
    /// hosts file is still in force, so the failure degrades the block instead of ending it.
    pub fn start(listen: &str, upstream: SocketAddr) -> std::io::Result<Self> {
        let socket = UdpSocket::bind(listen)?;
        socket.set_read_timeout(Some(std::time::Duration::from_millis(250)))?;
        let address = socket.local_addr()?;

        let blocked = Arc::new(Mutex::new(BTreeSet::new()));
        let stop = Arc::new(AtomicBool::new(false));
        let proxy = Self {
            blocked: blocked.clone(),
            stop: stop.clone(),
            address,
            previous: Vec::new(),
            remembered: None,
        };

        std::thread::spawn(move || {
            let in_flight = Arc::new(AtomicUsize::new(0));
            let mut buffer = [0u8; MAX_MESSAGE + 1];
            while !stop.load(Ordering::Relaxed) {
                let Ok((length, from)) = socket.recv_from(&mut buffer) else { continue };
                let message = buffer[..length].to_vec();
                let decision = {
                    let blocked = blocked.lock().unwrap_or_else(|e| e.into_inner());
                    answer(&message, &blocked)
                };
                match decision {
                    Answer::Refuse { .. } => {
                        let _ = socket.send_to(&refusal(&message), from);
                    }
                    Answer::Forward => {
                        if in_flight.load(Ordering::Relaxed) >= MAX_IN_FLIGHT {
                            continue;
                        }
                        let Ok(socket) = socket.try_clone() else { continue };
                        in_flight.fetch_add(1, Ordering::Relaxed);
                        let in_flight = in_flight.clone();
                        std::thread::spawn(move || {
                            if let Some(reply) = forward(&message, upstream) {
                                let _ = socket.send_to(&reply, from);
                            }
                            in_flight.fetch_sub(1, Ordering::Relaxed);
                        });
                    }
                }
            }
        });

        Ok(proxy)
    }

    /// Replace the block list. Called on every enforcement pass, so a session that has just ended
    /// stops being enforced by the resolver within one tick rather than at the next reboot.
    pub fn set(&self, blocked: BTreeSet<String>) {
        *self.blocked.lock().unwrap_or_else(|e| e.into_inner()) = blocked;
    }
}

impl Proxy {
    /// Point the machine's interfaces at this proxy, remembering what they used before.
    ///
    /// `remember` is where that record is also written: a service that is killed rather than
    /// stopped never runs its own restore, and an uninstall that cannot find out what the machine
    /// used to resolve with would have to guess.
    pub fn take_over(&mut self, remember: Option<std::path::PathBuf>) -> std::io::Result<()> {
        self.previous = point_at_loopback()?;
        self.remembered = remember.clone();
        if let (Some(path), false) = (remember, self.previous.is_empty()) {
            if let Ok(text) = serde_json::to_string_pretty(&self.previous) {
                if let Some(parent) = path.parent() {
                    let _ = std::fs::create_dir_all(parent);
                }
                let _ = std::fs::write(path, text);
            }
        }
        Ok(())
    }
}

impl Drop for Proxy {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        // The resolvers go back before anything else. A machine left pointing at a proxy that has
        // stopped has no working internet, and the user would rightly call that Curfew breaking
        // their computer rather than blocking a website.
        give_back(&self.previous);
        if let Some(path) = &self.remembered {
            let _ = std::fs::remove_file(path);
        }
    }
}

/// Give the resolvers back from a record left by a service that did not stop cleanly.
///
/// Called by the uninstaller, which is the one path that must work even when everything else has
/// already gone wrong.
pub fn give_back_remembered(path: &std::path::Path) {
    let Ok(text) = std::fs::read_to_string(path) else { return };
    if let Ok(interfaces) = serde_json::from_str::<Vec<Interface>>(&text) {
        give_back(&interfaces);
    }
    let _ = std::fs::remove_file(path);
}

/// Ask the upstream resolver, from a fresh socket so replies cannot be crossed between queries.
fn forward(message: &[u8], upstream: SocketAddr) -> Option<Vec<u8>> {
    let bind = if upstream.is_ipv4() { "0.0.0.0:0" } else { "[::]:0" };
    let socket = UdpSocket::bind(bind).ok()?;
    socket.set_read_timeout(Some(UPSTREAM_TIMEOUT)).ok()?;
    socket.send_to(message, upstream).ok()?;

    let mut buffer = [0u8; 4096];
    let (length, from) = socket.recv_from(&mut buffer).ok()?;
    // Only from the resolver that was asked: anything else arriving on this port is somebody
    // answering a question they were not asked.
    if from.ip() != upstream.ip() {
        return None;
    }
    // And only if it answers *this* query. Matching the transaction id is the one cheap defence
    // against an off-path forgery arriving first.
    if length < 2 || buffer[0..2] != message[0..2] {
        return None;
    }
    Some(buffer[..length].to_vec())
}

#[cfg(test)]
mod proxy_tests {
    use super::*;

    fn query(name: &str) -> Vec<u8> {
        let mut message = vec![0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0];
        for label in name.split('.') {
            message.push(label.len() as u8);
            message.extend_from_slice(label.as_bytes());
        }
        message.extend_from_slice(&[0, 0, 1, 0, 1]);
        message
    }

    /// A resolver that answers everything with a byte of its own, so a forwarded query is
    /// distinguishable from a refused one without needing the internet.
    fn fake_upstream() -> (SocketAddr, std::sync::mpsc::Receiver<String>) {
        let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        let address = socket.local_addr().unwrap();
        let (tx, rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            let mut buffer = [0u8; 512];
            while let Ok((length, from)) = socket.recv_from(&mut buffer) {
                let name = question(&buffer[..length]).unwrap_or_default();
                let _ = tx.send(name);
                let mut reply = buffer[..length].to_vec();
                reply[2] |= 0x80;
                reply.push(0xff);
                let _ = socket.send_to(&reply, from);
            }
        });
        (address, rx)
    }

    fn client(proxy: &Proxy, name: &str) -> Option<Vec<u8>> {
        let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        socket.set_read_timeout(Some(std::time::Duration::from_secs(2))).unwrap();
        socket.send_to(&query(name), proxy.address).unwrap();
        let mut buffer = [0u8; 512];
        let (length, _) = socket.recv_from(&mut buffer).ok()?;
        Some(buffer[..length].to_vec())
    }

    #[test]
    fn a_blocked_name_is_answered_here_and_never_reaches_the_upstream_resolver() {
        let (upstream, asked) = fake_upstream();
        let proxy = Proxy::start("127.0.0.1:0", upstream).unwrap();
        proxy.set(["reddit.com".to_string()].into_iter().collect());

        let reply = client(&proxy, "old.reddit.com").expect("no answer came back");

        assert_eq!(reply[reply.len() - 4..], [0, 0, 0, 0]);
        // The name is not leaked to anyone either: a blocker that phones a resolver about every
        // blocked site would be handing over a browsing history nobody agreed to.
        assert!(asked.try_recv().is_err(), "a blocked lookup was sent upstream anyway");
    }

    #[test]
    fn everything_else_is_forwarded_and_the_real_answer_comes_back_untouched() {
        let (upstream, asked) = fake_upstream();
        let proxy = Proxy::start("127.0.0.1:0", upstream).unwrap();
        proxy.set(["reddit.com".to_string()].into_iter().collect());

        let reply = client(&proxy, "docs.rs").expect("no answer came back");

        assert_eq!(asked.recv_timeout(std::time::Duration::from_secs(2)).unwrap(), "docs.rs");
        assert_eq!(reply[reply.len() - 1], 0xff, "the upstream's own answer was not passed through");
    }

    #[test]
    fn a_block_that_ends_stops_being_enforced_by_the_resolver_within_one_pass() {
        let (upstream, _asked) = fake_upstream();
        let proxy = Proxy::start("127.0.0.1:0", upstream).unwrap();
        proxy.set(["reddit.com".to_string()].into_iter().collect());
        client(&proxy, "reddit.com").unwrap();

        proxy.set(BTreeSet::new());

        let reply = client(&proxy, "reddit.com").expect("no answer came back");
        assert_eq!(reply[reply.len() - 1], 0xff, "the site was still refused after the block ended");
    }

    #[test]
    fn an_answer_from_somewhere_other_than_the_resolver_that_was_asked_is_thrown_away() {
        let mut message = query("docs.rs");
        message[0..2].copy_from_slice(&[0xaa, 0xbb]);
        let (upstream, _asked) = fake_upstream();
        let reply = forward(&message, upstream).expect("the real answer was rejected");
        assert_eq!(reply[0..2], [0xaa, 0xbb]);

        let elsewhere: SocketAddr = "127.0.0.2:9".parse().unwrap();
        assert!(forward(&message, elsewhere).is_none());
    }

    #[test]
    fn a_second_curfew_cannot_take_the_port_from_the_first() {
        let (upstream, _asked) = fake_upstream();
        let first = Proxy::start("127.0.0.1:0", upstream).unwrap();
        let taken = format!("127.0.0.1:{}", first.address.port());
        assert!(
            Proxy::start(&taken, upstream).is_err(),
            "binding twice would leave two resolvers disagreeing about what is blocked"
        );
    }
}

// --- pointing the machine at it -------------------------------------------------------------

/// What one network interface was told to use for DNS before Curfew touched it.
///
/// Kept so it can be given back exactly. A blocker that leaves a machine pointed at a resolver that
/// is no longer running has broken the internet on its way out, which is unforgivable — and one
/// that "restores" a statically configured office DNS to DHCP has done the same thing more quietly.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Interface {
    pub name: String,
    /// True when the interface was taking its resolvers from DHCP.
    pub dhcp: bool,
    /// The statically configured resolvers, in order, when it was not.
    pub servers: Vec<String>,
}

/// Read `netsh interface ipv4 show dnsservers` output.
///
/// Parsed rather than shelled around because the restore depends on it: getting this wrong means
/// handing back the wrong resolver, and the failure would show up as "the internet is broken" long
/// after Curfew was uninstalled.
pub fn parse_interfaces(output: &str) -> Vec<Interface> {
    let mut interfaces: Vec<Interface> = Vec::new();
    for line in output.lines() {
        let trimmed = line.trim();
        if let Some(rest) = trimmed.strip_prefix("Configuration for interface ") {
            interfaces.push(Interface {
                name: rest.trim().trim_matches('"').to_string(),
                dhcp: false,
                servers: Vec::new(),
            });
            continue;
        }
        let Some(current) = interfaces.last_mut() else { continue };

        if trimmed.starts_with("DNS servers configured through DHCP") {
            current.dhcp = true;
            continue;
        }
        if trimmed.starts_with("Statically Configured DNS Servers") {
            if let Some(value) = trimmed.split(':').nth(1) {
                let value = value.trim();
                if !value.is_empty() && value != "None" {
                    current.servers.push(value.to_string());
                }
            }
            continue;
        }
        // A continuation line: the second and later addresses of a static list are given on their
        // own, indented under the first.
        if !current.dhcp
            && !trimmed.is_empty()
            && trimmed.split('.').count() == 4
            && trimmed.chars().all(|c| c.is_ascii_digit() || c == '.')
        {
            current.servers.push(trimmed.to_string());
        }
    }
    interfaces
}

/// The commands that point every interface at Curfew, and the ones that give them back.
///
/// Returned as arguments rather than run here so the decision can be tested without a machine —
/// which matters, because the second list is the one that has to be right even when Curfew is being
/// removed.
pub fn repoint(interfaces: &[Interface]) -> Vec<Vec<String>> {
    interfaces
        .iter()
        .map(|interface| {
            vec![
                "interface".into(),
                "ipv4".into(),
                "set".into(),
                "dnsservers".into(),
                format!("name={}", interface.name),
                "static".into(),
                "127.0.0.1".into(),
                "primary".into(),
            ]
        })
        .collect()
}

pub fn restore(interfaces: &[Interface]) -> Vec<Vec<String>> {
    let mut commands = Vec::new();
    for interface in interfaces {
        let name = format!("name={}", interface.name);
        if interface.dhcp || interface.servers.is_empty() {
            commands.push(vec![
                "interface".into(),
                "ipv4".into(),
                "set".into(),
                "dnsservers".into(),
                name,
                "dhcp".into(),
            ]);
            continue;
        }
        commands.push(vec![
            "interface".into(),
            "ipv4".into(),
            "set".into(),
            "dnsservers".into(),
            name.clone(),
            "static".into(),
            interface.servers[0].clone(),
            "primary".into(),
        ]);
        for (index, server) in interface.servers.iter().enumerate().skip(1) {
            commands.push(vec![
                "interface".into(),
                "ipv4".into(),
                "add".into(),
                "dnsservers".into(),
                name.clone(),
                server.clone(),
                format!("index={}", index + 1),
            ]);
        }
    }
    commands
}

#[cfg(windows)]
mod sys {
    use super::*;

    fn netsh(args: &[String]) -> std::io::Result<()> {
        let status = std::process::Command::new("netsh").args(args).status()?;
        match status.success() {
            true => Ok(()),
            false => Err(std::io::Error::other(format!("netsh {}: {status}", args.join(" ")))),
        }
    }

    /// Read what every interface is using now.
    pub fn interfaces() -> std::io::Result<Vec<Interface>> {
        let output = std::process::Command::new("netsh")
            .args(["interface", "ipv4", "show", "dnsservers"])
            .output()?;
        Ok(parse_interfaces(&String::from_utf8_lossy(&output.stdout)))
    }

    /// Point every interface at Curfew, returning what they were using before.
    pub fn point_at_loopback() -> std::io::Result<Vec<Interface>> {
        let before = interfaces()?;
        for args in repoint(&before) {
            netsh(&args)?;
        }
        Ok(before)
    }

    /// Give the machine's resolvers back. Best effort, and every interface is attempted even if an
    /// earlier one failed: leaving the rest pointed at a resolver that has stopped would be worse.
    pub fn give_back(before: &[Interface]) {
        for args in restore(before) {
            let _ = netsh(&args);
        }
    }
}

#[cfg(not(windows))]
mod sys {
    use super::Interface;

    pub fn interfaces() -> std::io::Result<Vec<Interface>> {
        Ok(Vec::new())
    }

    /// Nothing to repoint: everywhere but Windows, the proxy is reached by pointing at it manually.
    pub fn point_at_loopback() -> std::io::Result<Vec<Interface>> {
        Ok(Vec::new())
    }

    pub fn give_back(_before: &[Interface]) {}
}

pub use sys::{give_back, interfaces, point_at_loopback};

#[cfg(test)]
mod interface_tests {
    use super::*;

    const OUTPUT: &str = "\r
Configuration for interface \"Ethernet\"\r
    DNS servers configured through DHCP:  192.168.1.1\r
    Register with which suffix:           Primary only\r
\r
Configuration for interface \"Wi-Fi\"\r
    Statically Configured DNS Servers:    1.1.1.1\r
                                          8.8.8.8\r
    Register with which suffix:           Primary only\r
\r
Configuration for interface \"Loopback Pseudo-Interface 1\"\r
    Statically Configured DNS Servers:    None\r
    Register with which suffix:           None\r
";

    #[test]
    fn every_interface_is_read_with_where_its_resolvers_came_from() {
        let interfaces = parse_interfaces(OUTPUT);
        assert_eq!(interfaces.len(), 3);
        assert_eq!(interfaces[0].name, "Ethernet");
        assert!(interfaces[0].dhcp);
        assert_eq!(interfaces[1].servers, vec!["1.1.1.1", "8.8.8.8"]);
        assert!(!interfaces[1].dhcp);
        assert!(interfaces[2].servers.is_empty(), "\"None\" is not a resolver");
    }

    #[test]
    fn nothing_is_invented_from_output_that_makes_no_sense() {
        assert!(parse_interfaces("").is_empty());
        assert!(parse_interfaces("The requested operation requires elevation.").is_empty());
    }

    #[test]
    fn every_interface_is_pointed_at_curfew_and_none_is_missed() {
        let commands = repoint(&parse_interfaces(OUTPUT));
        assert_eq!(commands.len(), 3, "an interface left alone is a way round the resolver");
        assert!(commands.iter().all(|c| c.contains(&"127.0.0.1".to_string())));
    }

    #[test]
    fn a_dhcp_interface_is_given_back_to_dhcp_rather_than_to_a_guess() {
        let commands = restore(&parse_interfaces(OUTPUT));
        assert!(commands[0].contains(&"dhcp".to_string()));
        assert!(commands[0].contains(&"name=Ethernet".to_string()));
    }

    #[test]
    fn a_statically_configured_interface_gets_back_every_resolver_in_its_own_order() {
        // Restoring an office's static DNS as "DHCP" is the same outage as not restoring it at all,
        // and losing the secondary is an outage that only shows up when the primary goes down.
        let commands = restore(&parse_interfaces(OUTPUT));
        let wifi: Vec<&Vec<String>> =
            commands.iter().filter(|c| c.contains(&"name=Wi-Fi".to_string())).collect();
        assert_eq!(wifi.len(), 2);
        assert!(wifi[0].contains(&"1.1.1.1".to_string()) && wifi[0].contains(&"primary".to_string()));
        assert!(wifi[1].contains(&"8.8.8.8".to_string()) && wifi[1].contains(&"index=2".to_string()));
    }

    #[test]
    fn an_interface_with_no_resolvers_at_all_is_handed_back_to_dhcp() {
        let commands = restore(&parse_interfaces(OUTPUT));
        let loopback: Vec<&Vec<String>> = commands
            .iter()
            .filter(|c| c.contains(&"name=Loopback Pseudo-Interface 1".to_string()))
            .collect();
        assert_eq!(loopback.len(), 1);
        assert!(loopback[0].contains(&"dhcp".to_string()));
    }
}
