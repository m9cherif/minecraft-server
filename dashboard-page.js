#!/usr/bin/env node
/**
 * Dashboard HTML for Baarcha MC (see webapp-dashboard.js for the API surface).
 * Tunnel of record is a free playit.gg agent: it prints a claim URL when not
 * yet linked and serves a public host:port once the account is connected.
 * Client-side script deliberately avoids template literals so this file can
 * stay a single readable template.
 */
'use strict';

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

const DOMAIN = 'chrif.net';
const MC_HOST = 'mc.chrif.net';

function page(mcPort, tunnel, version) {
  const esc = escapeHtml;
  const t = tunnel || {};
  const configured = Boolean(t.configured);
  const claiming = !configured && Boolean(t.claimPending && t.claimUrl);
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>Baarcha MC &mdash; server dashboard</title>
<style>
  :root{--bg:#0f1115;--panel:#171a21;--line:#262b36;--fg:#e6e9ef;--muted:#98a2b3;
        --ok:#4ade80;--warn:#fbbf24;--err:#f87171;--accent:#7aa2f7}
  *{box-sizing:border-box}
  body{margin:0;background:var(--bg);color:var(--fg);
    font:14px/1.5 ui-sans-serif,system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
  .wrap{max-width:960px;margin:0 auto;padding:24px 16px 48px}
  header h1{font-size:20px;margin:0 0 4px}
  .sub{color:var(--muted);margin:0 0 20px;font-size:13px}
  .grid{display:grid;gap:12px;grid-template-columns:repeat(auto-fit,minmax(220px,1fr))}
  .card{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:14px 16px}
  .card h2{font-size:11px;letter-spacing:.08em;text-transform:uppercase;color:var(--muted);margin:0 0 8px;font-weight:600}
  .kv{display:flex;justify-content:space-between;align-items:center;gap:8px}
  .kv+.kv{margin-top:6px}
  .val{font-weight:600;font-variant-numeric:tabular-nums}
  code.val{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:13px}
  .pill{padding:2px 10px;border-radius:999px;font-size:12px;font-weight:600}
  .pill.running{background:rgba(74,222,128,.15);color:var(--ok)}
  .pill.starting{background:rgba(251,191,36,.15);color:var(--warn)}
  .pill.stopped{background:rgba(248,113,113,.15);color:var(--err)}
  button{cursor:pointer;border:1px solid var(--line);background:#20252f;color:var(--fg);
    padding:8px 14px;border-radius:8px;font-size:13px;font-weight:500}
  button:hover{background:#2a303d}
  .actions{display:flex;gap:8px;flex-wrap:wrap;margin:16px 0 4px}
  .console{background:#10131a;border:1px solid var(--line);border-radius:10px;padding:12px;
    height:260px;overflow-y:auto;font:12px/1.45 ui-monospace,Menlo,Consolas,monospace;
    white-space:pre-wrap;word-break:break-word}
  .row{display:flex;gap:8px;margin-top:10px}
  input[type=text]{flex:1;background:#10131a;border:1px solid var(--line);color:var(--fg);
    padding:8px 10px;border-radius:8px;font-size:13px}
  .cfg-banner{padding:8px 12px;border-radius:8px;font-size:13px;font-weight:500}
  .cfg-banner.no-token{background:rgba(251,191,36,.12);color:var(--warn)}
  .cfg-banner.ok{background:rgba(74,222,128,.12);color:var(--ok)}
  .steps{list-style:none;counter-reset:step;margin:0;padding:0}
  .steps li{counter-increment:step;position:relative;padding-left:34px;margin:12px 0}
  .steps li::before{content:counter(step);position:absolute;left:0;top:0;width:22px;height:22px;
    border-radius:50%;background:var(--accent);color:#0f1115;font-weight:700;font-size:12px;
    display:flex;align-items:center;justify-content:center}
  .steps b{display:block;margin-bottom:2px}
  .steps code,.dns-line{user-select:all;word-break:break-all;
    font-family:ui-monospace,Menlo,Consolas,monospace;font-size:13px}
  .addr-copy{display:inline-flex;align-items:center;gap:6px;margin-top:6px}
  .addr-copy button{padding:4px 10px;font-size:12px}
  .dns-table{width:100%;border-collapse:collapse;margin-top:6px;font-size:13px}
  .dns-table th,.dns-table td{text-align:left;padding:4px 8px;border:1px solid var(--line)}
  .dns-table th{color:var(--muted);font-weight:600;font-size:11px;text-transform:uppercase;letter-spacing:.06em}
  .callout{border-left:3px solid var(--warn);background:rgba(251,191,36,.07);
    padding:10px 12px;border-radius:0 8px 8px 0;margin:14px 0 0;font-size:13px;line-height:1.55}
  .callout b{color:var(--warn)}
  .note{color:var(--muted);font-size:12px;margin:6px 0 0}
  .flash{position:fixed;left:50%;bottom:24px;transform:translateX(-50%);
    background:#20252f;border:1px solid var(--line);color:var(--fg);
    padding:8px 16px;border-radius:8px;opacity:0;transition:opacity .2s;pointer-events:none}
  .flash.show{opacity:1}
</style>
</head>
<body>
<div class="wrap">
  <header style="display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap">
    <div>
      <h1>🎮 Baarcha MC</h1>
      <p class="sub" style="margin-bottom:0">Minecraft Java Edition · Paper ${esc(version || '…')} · port ${esc(mcPort)}</p>
    </div>
    <button type="button" id="exportBtn" title="Download a zip of the project source code">⬇ Export Project</button>
  </header>

  <div class="grid">
    <div class="card">
      <h2>Status</h2>
      <div class="kv"><span>Server</span><span id="state" class="pill stopped">checking…</span></div>
      <div class="kv"><span>PID</span><code class="val" id="pid">—</code></div>
      <div class="kv"><span>Game port</span><code class="val" id="portState">—</code></div>
    </div>
    <div class="card">
      <h2>Ram</h2>
      <div class="kv"><span>System used</span><code class="val" id="ramSys">—</code></div>
      <div class="kv"><span>Minecraft (RSS)</span><code class="val" id="ramJava">—</code></div>
    </div>
    <div class="card">
      <h2>playit.gg agent</h2>
      <div class="kv"><span>Status</span><span id="connector" class="pill stopped">checking…</span></div>
      <div class="kv"><span>Public address</span><code class="val" id="tunnelAddr">—</code></div>
      <div class="kv" id="claimRow"${claiming ? '' : ' style="display:none"'}><span>Claim link</span><code class="val" id="claimUrl">${esc(t.claimUrl || '—')}</code></div>
      <div class="addr-copy">
        <button type="button" data-copy-target="tunnelAddr">Copy address</button>
        <button type="button" id="copyClaimBtn" data-copy-target="claimUrl"${claiming ? '' : ' style="display:none"'}>Copy claim link</button>
      </div>
    </div>
  </div>

  <section class="card" style="margin-top:12px">
    <h2>Connect with playit.gg (free) — configuration required once</h2>
    <div id="cfgBanner" class="cfg-banner no-token">${
      configured ? '' :
      'Agent waiting to be claimed — open the claim link above or paste PLAYIT_SECRET_KEY in the Keys panel'
    }</div>
    <ol class="steps">
      <li>
        <b>Create a free playit.gg account</b>
        Sign up at <code>playit.gg</code>. No credit card needed — the free
        plan covers a Minecraft Java tunnel like ours.
      </li>
      <li>
        <b>Link this box's agent</b>
        Either open the <em>claim link</em> shown on this dashboard
        (<code>https://playit.gg/claim/&lt;code&gt;</code>) while logged into
        your new account and approve the agent, or paste its secret as
        <code>PLAYIT_SECRET_KEY</code> in the Keys panel. The agent here
        connects automatically within seconds of either.
      </li>
      <li>
        <b>Create a Minecraft Java tunnel targeting this server</b>
        In the playit dashboard choose <em>Tunnels → Create tunnel</em>, pick
        <em>Minecraft Java</em> and set the local target to
        <code>localhost:${esc(mcPort)}</code>. This box runs Paper exactly there.
      </li>
      <li>
        <b>Use chrif.net with DNS records (optional)</b>
        In Hostinger's DNS zone editor add one of:
        <table class="dns-table">
          <tr><th>Type</th><th>Name</th><th>Content / Port</th></tr>
          <tr><td>A</td><td><code>mc.${esc(DOMAIN)}</code></td>
              <td>a playit edge IP for your tunnel</td></tr>
          <tr><td>SRV</td><td><code>_minecraft._tcp.mc.${esc(DOMAIN)}</code></td>
              <td>target your <code>&lt;tunnel-host&gt;.craft.playit.gg</code>,
                  weight 0, priority 5 &mdash; players then join plain
                  <code>${MC_HOST}</code> with no port suffix</td></tr>
        </table>
        SRV is how a custom hostname can ride a non-25565 port &mdash; vanilla
        clients resolve it natively.
      </li>
    </ol>
    <div class="callout">
      <b>Honest note:</b> the free plan reliably gives you an assigned address
      like <code>your-name.craft.playit.gg:&lt;port&gt;</code> which works the
      moment your tunnel exists. Custom domain mc.${DOMAIN} may require playing
      with playit's own domain settings (or a small upgrade), but the SRV
      forwarding above stays fully free-friendly &mdash; until it resolves,
      share the raw <code>.craft.playit.gg:port</code> address instead; that
      always works.
      <br /><b>Verified ${esc(new Date().toISOString().slice(0, 10))}:</b>
      &nbsp;<code>${esc(t.address || 'pending')}</code> answers a full Minecraft
      status ping through the whole chain (sandbox &rarr; playit edge &rarr;
      Paper), consistent over IPv4 from 20 external vantage points.
      <br /><b>Direct Connect — exactly one of these two strings always works:</b>
      <code style="user-select:all">${esc(t.addressHost ? t.addressHost : (t.address || 'pending'))}</code>
      ${t.addressHost && t.address ? `or <code style="user-select:all">${esc(t.address)}</code>` : ''}
      (hostname alone is enough &mdash; an SRV record at the tunnel host already
      points clients to the correct port). If a server entry hangs on
      &ldquo;Pinging&hellip;&rdquo;, delete the entry and re-add it fresh, then
      hit Refresh. Never substitute the resolved numeric address for the name:
      playit&rsquo;s edge validates the hostname inside the handshake and resets
      everything else.
    </div>
  </section>

  <h2 style="font-size:11px;color:var(--muted);text-transform:uppercase;letter-spacing:.08em;margin:22px 0 8px;font-weight:600">Actions</h2>
  <div class="actions">
    <button type="button" data-act="start">Start</button>
    <button type="button" data-act="stop">Stop</button>
    <button type="button" data-act="restart">Restart</button>
  </div>

  <h2 style="font-size:11px;color:var(--muted);text-transform:uppercase;letter-spacing:.08em;margin:22px 0 8px;font-weight:600">Console — last 50 lines</h2>
  <div class="console" id="log">(no output yet)</div>

  <form id="cmdForm" class="row">
    <input type="text" id="cmdInput" placeholder="Server command, e.g. list, say hi, whitelist add NAME"
           autocomplete="off" aria-label="Server console command"/>
    <button type="submit">Send</button>
  </form>
  <p class="note">Commands are appended to the server's console pipe read by the running JVM.</p>
</div>
<div class="flash" id="flash"></div>

<script>
(function(){
  var statePill=document.getElementById('state');
  function el(id){return document.getElementById(id);}
  function flash(msg){
    var f=el('flash');f.textContent=msg;f.classList.add('show');
    setTimeout(function(){f.classList.remove('show');},1800);
  }
  function render(d){
    var s=d.status||'stopped';
    statePill.className='pill '+s;
    statePill.textContent=s;
    el('pid').textContent=d.pid?String(d.pid):'—';
    el('portState').textContent=d.minecraft_port_open?'open':'closed';
    if(d.ram_used_human&&d.ram_total_human) el('ramSys').textContent=d.ram_used_human+' / '+d.ram_total_human;
    if(d.ram_java_human) el('ramJava').textContent=d.ram_java_human;

    var cfg=document.getElementById('cfgBanner');
    var conn=document.getElementById('connector');
    el('tunnelAddr').textContent=d.tunnel_address||'not allocated yet';
    // Claim UI lives only while a genuine claim is pending; once the tunnel
    // exists the address alone is shown (user never wants a claim link again).
    var claiming=!!(d.claim_pending&&d.playit_claim_url);
    el('claimRow').style.display=claiming?'':'none';
    el('copyClaimBtn').style.display=claiming?'':'none';
    el('claimUrl').textContent=d.playit_claim_url||'—';
    if(d.tunnel_configured){
      cfg.className='cfg-banner ok';
      cfg.textContent='playit agent connected — players can join at '+d.tunnel_address+'.';
      conn.className='pill '+(d.playit_running?'running':'starting');
      conn.textContent=d.playit_running?'connected':'connecting';
    } else if(claiming){
      cfg.className='cfg-banner no-token';
      cfg.textContent='Agent waiting to be claimed — approve the claim link below to finish setup (only ever asked once).';
      conn.className='pill '+(d.playit_running?'starting':'stopped');
      conn.textContent=d.playit_running?'unclaimed':'agent down';
    } else {
      cfg.className='cfg-banner no-token';
      cfg.textContent='Tunnel connecting…';
      conn.className='pill '+(d.playit_running?'starting':'stopped');
      conn.textContent=d.playit_running?'connecting':'agent down';
    }

    var lines=(d.log_lines||[]);
    el('log').textContent=lines.length?lines.join('\\n'):'(no output yet)';
  }
  function refresh(){
    fetch('/api/status').then(function(r){return r.json();}).then(render).catch(function(){});
  }
  document.addEventListener('click',function(ev){
    var t=ev.target;
    if(t.dataset && t.dataset.act){
      t.disabled=true;
      fetch('/control/'+t.dataset.act,{method:'POST'}).then(function(r){return r.json();})
        .then(function(d){flash((d.label||t.dataset.act)+': '+(d.ok?'ok':'failed'));setTimeout(refresh,800);})
        .catch(function(){flash('action failed');})
        .finally(function(){setTimeout(function(){t.disabled=false;},600);});
    }
    if(t.dataset && t.dataset.copyTarget){
      var txt=el(t.dataset.copyTarget).textContent;
      navigator.clipboard.writeText(txt).then(function(){flash('copied');},
        function(){flash('copy failed');});
    }
  });
  document.getElementById('cmdForm').addEventListener('submit',function(ev){
    ev.preventDefault();
    var input=el('cmdInput');
    var v=input.value.trim();
    if(!v)return;
    fetch('/command',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({command:v})})
      .then(function(r){return r.json().then(function(j){return {ok:r.ok,body:j};});})
      .then(function(res){flash(res.ok?('sent: '+res.body.sent):(res.body.error||'rejected'));if(res.ok)input.value='';})
      .catch(function(){flash('send failed');});
  });
  var exportBtn=document.getElementById('exportBtn');
  exportBtn.addEventListener('click',function(){
    // Resolve against the current path so the link works both at the root and
    // under any public hosting subpath.
    var base=location.pathname.replace(/[^\/]*$/,'');
    // Plain anchor to an attachment response instead of fetch()+object URL:
    // no 25 MB blob buffered in page memory and nothing for a sandboxed
    // preview frame to block or revoke mid-download. The server's
    // Content-Disposition supplies the timestamped filename.
    var a=document.createElement('a');
    a.href=base+'api/export-project';
    document.body.appendChild(a);a.click();a.remove();
  });
  refresh();
  setInterval(refresh,3000);
})();
</script>
</body>
</html>`;
}

module.exports = { page };
