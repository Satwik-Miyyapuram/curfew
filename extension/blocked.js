// The reason is written by the service, never by this page: a block page that made up its own
// explanation could tell the reader something the engine did not decide.
const params = new URLSearchParams(location.search);
const reason = params.get("reason");
if (reason) document.getElementById("reason").textContent = reason;
document.getElementById("url").textContent = params.get("url") || "";
