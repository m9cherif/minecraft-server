'use strict';

// The status page. One self-contained HTML document — no external CSS, fonts or
// scripts — so it loads on a shared host with nothing else deployed alongside
// it, and keeps working if the CDN of the week is blocked.

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
  })[c]);
}

function renderPage(config) {
  const name = escapeHtml(config.mc.name);
  const address = escapeHtml(config.mc.address);
  const voicePort = config.mc.voicePort;

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>${name} — server status</title>
<style>
  :root {
    color-scheme: light dark;
    --bg: #f6f7f9;
    --card: #ffffff;
    --ink: #16181d;
    --muted: #5f6572;
    --line: #e3e6ea;
    --up: #1a7f47;
    --down: #b3261e;
    --accent: #3a5ccc;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --bg: #14161a;
      --card: #1c1f25;
      --ink: #e9ecf1;
      --muted: #99a1b0;
      --line: #2b3039;
      --up: #5ed99a;
      --down: #ff8a80;
      --accent: #8fa8ff;
    }
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    padding: 2.5rem 1.25rem 4rem;
    background: var(--bg);
    color: var(--ink);
    font: 16px/1.55 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  }
  main { max-width: 42rem; margin: 0 auto; }
  h1 { font-size: 1.6rem; margin: 0 0 .25rem; letter-spacing: -.01em; }
  .sub { color: var(--muted); margin: 0 0 1.75rem; }
  .card {
    background: var(--card);
    border: 1px solid var(--line);
    border-radius: 12px;
    padding: 1.25rem 1.35rem;
    margin-bottom: 1rem;
  }
  .state { display: flex; align-items: center; gap: .6rem; font-weight: 600; font-size: 1.1rem; }
  .dot { width: .7rem; height: .7rem; border-radius: 50%; background: var(--muted); flex: none; }
  .up .dot { background: var(--up); }
  .down .dot { background: var(--down); }
  .up { color: var(--up); }
  .down { color: var(--down); }
  .motd {
    margin: .85rem 0 0;
    white-space: pre-wrap;
    color: var(--muted);
    font-size: .95rem;
  }
  dl { display: grid; grid-template-columns: max-content 1fr; gap: .5rem 1.25rem; margin: 1rem 0 0; }
  dt { color: var(--muted); font-size: .9rem; }
  dd { margin: 0; font-variant-numeric: tabular-nums; }
  code, .addr {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: .93em;
    background: color-mix(in srgb, var(--ink) 7%, transparent);
    padding: .12em .4em;
    border-radius: 5px;
  }
  h2 { font-size: .8rem; text-transform: uppercase; letter-spacing: .07em; color: var(--muted); margin: 0 0 .75rem; }
  ul.players { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: .4rem; }
  ul.players li { border: 1px solid var(--line); border-radius: 999px; padding: .15rem .7rem; font-size: .9rem; }
  footer { color: var(--muted); font-size: .85rem; text-align: center; margin-top: 1.5rem; }
  button {
    font: inherit; font-size: .9rem; cursor: pointer; color: var(--accent);
    background: none; border: 0; padding: 0; text-decoration: underline;
  }
  .hidden { display: none; }
</style>
</head>
<body>
<main>
  <h1>${name}</h1>
  <p class="sub">Join at <span class="addr">${address}</span></p>

  <section class="card">
    <div id="state" class="state"><span class="dot"></span><span id="state-text">Checking…</span></div>
    <p id="motd" class="motd hidden"></p>
    <dl id="details" class="hidden">
      <dt>Players</dt><dd id="players">—</dd>
      <dt>Version</dt><dd id="version">—</dd>
      <dt>Latency</dt><dd id="latency">—</dd>
    </dl>
    <p id="error" class="motd hidden"></p>
  </section>

  <section id="sample-card" class="card hidden">
    <h2>Online now</h2>
    <ul id="sample" class="players"></ul>
  </section>

  ${
    voicePort
      ? `<section class="card">
    <h2>Voice chat</h2>
    <p class="motd" style="margin-top:0">Simple Voice Chat runs on UDP port <code>${voicePort}</code>.
    It is a separate port from the game and needs its own forward — if you can play but voice
    reports “unable to connect”, that is the reason.</p>
  </section>`
      : ''
  }

  <footer>
    <span id="updated">—</span> · <button id="refresh" type="button">Refresh</button>
  </footer>
</main>
<script>
(function () {
  var stateEl = document.getElementById('state');
  var stateText = document.getElementById('state-text');
  var motd = document.getElementById('motd');
  var details = document.getElementById('details');
  var errorEl = document.getElementById('error');
  var sampleCard = document.getElementById('sample-card');
  var sample = document.getElementById('sample');
  var updated = document.getElementById('updated');

  function show(el, on) { el.classList.toggle('hidden', !on); }

  function render(data) {
    stateEl.className = 'state ' + (data.online ? 'up' : 'down');
    stateText.textContent = data.online ? 'Online' : 'Offline';

    show(motd, Boolean(data.motd));
    if (data.motd) motd.textContent = data.motd;

    show(details, data.online);
    if (data.online) {
      var p = data.players || {};
      document.getElementById('players').textContent =
        (p.online == null ? '?' : p.online) + ' / ' + (p.max == null ? '?' : p.max);
      document.getElementById('version').textContent = data.version || 'unknown';
      document.getElementById('latency').textContent =
        data.latencyMs == null ? 'unknown' : data.latencyMs + ' ms';
    }

    show(errorEl, !data.online && Boolean(data.error));
    if (!data.online && data.error) errorEl.textContent = data.error;

    var names = (data.players && data.players.sample) || [];
    show(sampleCard, names.length > 0);
    sample.replaceChildren.apply(sample, names.map(function (n) {
      var li = document.createElement('li');
      li.textContent = n;
      return li;
    }));

    updated.textContent = 'Checked ' + new Date(data.checkedAt).toLocaleTimeString();
  }

  function load() {
    updated.textContent = 'Checking…';
    fetch('api/status', { headers: { accept: 'application/json' } })
      .then(function (r) { return r.json(); })
      .then(render)
      .catch(function (err) {
        render({ online: false, error: 'Could not reach the status API: ' + err.message,
                 checkedAt: new Date().toISOString() });
      });
  }

  document.getElementById('refresh').addEventListener('click', load);
  // The server caches pings, so a 30 s poll costs one TCP connection at most.
  setInterval(load, 30000);
  load();
})();
</script>
</body>
</html>
`;
}

module.exports = { renderPage, escapeHtml };
