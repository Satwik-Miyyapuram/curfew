# The Curfew browser extension

The service can see that a browser is running and it can stop a name resolving. What it cannot see
is which page a tab is on — so a rule like `*youtube.com/shorts*`, which blocks the shorts feed and
leaves the rest of the site alone, needs something inside the browser.

That is all this is. It reports the URL and shows what it is told; every decision is made by the
service, which owns the rules and the locks. **It is never the enforcement floor**: while a URL or
keyword rule is running, a browser that is not sending heartbeats is closed outright by the service.
Removing the extension costs you the browser rather than buying back the sites.

## Installing it

1. Tell the browser the native-messaging host exists, using the id from the browser's own
   extensions page:

   ```
   curfew extension chrome <extension-id>
   ```

   `chrome`, `edge`, `brave`, `vivaldi`, `chromium`, `firefox` and `librewolf` are understood. Run it
   once per browser you use — an unregistered browser gets closed rather than exempted.

2. Load this folder as an unpacked extension (`chrome://extensions` with developer mode on, or
   `about:debugging` in Firefox), then restart the browser.

## What it can and cannot do

* It sees the URL of the top-level frame on every navigation, including the ones single-page apps do
  without loading a page.
* It sends nothing anywhere. The only thing it talks to is the Curfew service on this machine,
  through a pipe the browser itself opens.
* If the service is unreachable it allows everything. Failing closed would turn a stopped service
  into a browser that shows nothing but block pages — and the service closes an unwatched browser on
  its own, so nothing is lost by being permissive here.
