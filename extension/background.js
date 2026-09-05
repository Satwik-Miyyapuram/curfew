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

function beat() {
  ask({ type: "beat", browser: BROWSER });
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

chrome.alarms.create("curfew-beat", { periodInMinutes: BEAT_SECONDS / 60 });
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === "curfew-beat") beat();
});

chrome.runtime.onStartup.addListener(beat);
chrome.runtime.onInstalled.addListener(beat);
beat();
