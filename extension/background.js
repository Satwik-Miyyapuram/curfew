// The browser half of Curfew's granularity layer.
//
// It decides nothing. Every URL is put to the service, which owns the rules, the sessions and the
// locks; this file only reports what the tab is showing and does what it is told. That is
// deliberate: an extension can be edited by anyone who can open a folder, so an extension that
// could decide would be a way out of a lock, and a lock with a way out is not a lock.
//
// The other half of the arrangement lives in the service: while a URL rule is running, a browser
// that is not sending heartbeats gets closed. Removing this extension therefore costs the whole
// browser rather than buying back the sites.

const HOST = "com.curfew.host";
const BEAT_SECONDS = 20;
const BROWSER = guessBrowser();

let port = null;
let nextId = 1;
const waiting = new Map();

function guessBrowser() {
  // Only used so the service can tell which browser is reporting; a wrong guess costs nothing
  // beyond the service crediting the heartbeat to a name that is not running, which fails safe.
  const agent = navigator.userAgent;
  if (agent.includes("Edg/")) return "msedge.exe";
  if (agent.includes("Firefox/")) return "firefox.exe";
  if (agent.includes("OPR/")) return "opera.exe";
  if (agent.includes("Vivaldi")) return "vivaldi.exe";
  if (agent.includes("Brave")) return "brave.exe";
  return "chrome.exe";
}

function connect() {
  if (port) return port;
  try {
    port = chrome.runtime.connectNative(HOST);
  } catch (e) {
    port = null;
    return null;
  }
  port.onMessage.addListener((message) => {
    // Answers come back in the order they were asked; the host is a serial relay.
    const [id] = waiting.keys();
    if (id === undefined) return;
    const resolve = waiting.get(id);
    waiting.delete(id);
    resolve(message);
  });
  port.onDisconnect.addListener(() => {
    port = null;
    for (const resolve of waiting.values()) resolve(null);
    waiting.clear();
  });
  return port;
}

function ask(message, timeoutMs = 1500) {
  const live = connect();
  if (!live) return Promise.resolve(null);
  return new Promise((resolve) => {
    const id = nextId++;
    waiting.set(id, resolve);
    // A service that has gone away must not leave tabs hanging: no answer means allow, because
    // the service closes an unwatched browser by itself and a hung tab helps nobody.
    setTimeout(() => {
      if (waiting.delete(id)) resolve(null);
    }, timeoutMs);
    try {
      live.postMessage(message);
    } catch (e) {
      waiting.delete(id);
      resolve(null);
    }
  });
}

// The heartbeat carries the page in the focused tab, when one of this browser's windows is
// focused at all: that is what the service charges web budgets against, since it cannot see a tab
// from where it runs. A tab behind another program reports nothing and costs nothing.
async function beat() {
  let url = null;
  try {
    const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
    const focused = tab && (await chrome.windows.get(tab.windowId)).focused;
    if (focused && tab.url && /^https?:/i.test(tab.url)) url = tab.url;
  } catch (e) {
    // No tabs permission, no window: a beat with no page is still a beat.
  }
  ask({ type: "beat", browser: BROWSER, url });
}

async function check(tabId, url) {
  if (!url || !/^https?:/i.test(url)) return;
  const answer = await ask({ type: "check", browser: BROWSER, url });
  if (!answer || answer.type !== "verdict" || !answer.blocked) return;
  const page = chrome.runtime.getURL("blocked.html");
  const query = `?url=${encodeURIComponent(url)}&reason=${encodeURIComponent(answer.reason || "")}`;
  chrome.tabs.update(tabId, { url: page + query });
}

chrome.webNavigation.onBeforeNavigate.addListener((details) => {
  if (details.frameId !== 0) return;
  check(details.tabId, details.url);
});

// Single-page apps never navigate: on YouTube the whole point of a `*/shorts/*` rule is a URL that
// changes without a page load.
chrome.webNavigation.onHistoryStateUpdated.addListener((details) => {
  if (details.frameId !== 0) return;
  check(details.tabId, details.url);
});

// **A rule that starts while a matching page is already open** (P2-12).
//
// The two listeners above only fire on navigation, so a tab that was already sitting on a matching
// URL was never checked again: start a path rule with the page open and nothing happens, and the
// browser is not closed either, because the extension *is* beating. The user's only way out was to
// reload the page, which is not a thing anyone would think to try.
//
// Switching to the tab or bringing the browser forward is the moment the user is looking at the page
// and expecting the rule to be in force, so those are the two events to re-check on. Together they
// cover the ordinary route — the phone or the config changes, the user comes back to the browser —
// without polling every tab on every beat, which would spend a `check()` per open tab per beat and
// put the whole rule set on the wire sixty times a minute.
async function recheckFocused() {
  try {
    const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
    if (tab && tab.id !== undefined) check(tab.id, tab.url);
  } catch (e) {
    // No tabs permission, no window: nothing to re-check.
  }
}

chrome.tabs.onActivated.addListener(recheckFocused);
chrome.windows.onFocusChanged.addListener((windowId) => {
  // `WINDOW_ID_NONE` means the browser lost focus to another program, which is not a moment anybody
  // is reading a blocked page.
  if (windowId !== chrome.windows.WINDOW_ID_NONE) recheckFocused();
});

chrome.alarms.create("curfew-beat", { periodInMinutes: BEAT_SECONDS / 60 });
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === "curfew-beat") beat();
});

chrome.runtime.onStartup.addListener(beat);
chrome.runtime.onInstalled.addListener(beat);
beat();
