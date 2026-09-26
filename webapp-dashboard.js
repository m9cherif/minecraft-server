#!/usr/bin/env node
/**
 * Baarcha MC dashboard.
 *
 * GET  /            dashboard UI (browsers) or JSON summary (curl/CLI clients)
 * GET  /health      platform readiness probe
 * GET  /api/status  live snapshot: state, tunnel url, ram, console tail
 * GET  /api/export-project  zip of the project source (timestamped filename)
 * POST /control/{start,stop,restart}
 * POST /command     { command: "say hi" } -> Minecraft console stdin
 */
const express = require('express');
const fs = require('fs');
const net = require('net');
const path = require('path');
const { spawnSync } = require('child_process');
const dashboard = require('./dashboard-page');
const projectExport = require('./project-export');

const APP_DIR = __dirname;
const SERVER_DIR = path.join(APP_DIR, 'server');
const PID_FILE = path.join(SERVER_DIR, 'java.pid');
const CMDS_FILE = path.join(SERVER_DIR, 'console.cmds');
const LOG_CANDIDATES = [
  path.join(SERVER_DIR, 'logs', 'latest.log'),
  path.join(SERVER_DIR, 'logs', 'stdio.log'),
];
const MC_PORT = parseInt(process.env.MC_PORT || '25565', 10);

const app = express();
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// ---------- helpers ----------

function readFileSyncSafe(p) {
  try {
    return fs.readFileSync(p, 'utf8');
  } catch (_) {
    return null;
  }
}

function javaPid() {
  const raw = readFileSyncSafe(PID_FILE);
  if (!raw) return 0;
  const pid = parseInt(raw.trim(), 10);
  if (!Number.isFinite(pid) || pid <= 0) return 0;
  return isNodeRunning(pid) ? pid : 0;
}

/** Process liveness including children spawned into a different process group
    by the launcher; falls back to scanning /proc cmdline. */
function isNodeRunning(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (err) {
    return err.code === 'EPERM';
  }
}

function findJavaProcess() {
  const known = javaPid();
  if (known) return known;
  try {
    // Fallback scan: any running process whose cmdline mentions our paper.jar.
    const procDir = fs.readdirSync('/proc').filter((d) => /^\d+$/.test(d));
    for (const entry of procDir) {
      const cmd = readFileSyncSafe(`/proc/${entry}/cmdline`);
      if (!cmd) continue;
      if (cmd.includes('paper.jar')) return parseInt(entry, 10);
    }
  } catch (_) { /* fallthrough */ }
  return 0;
}

function probeMcPort(timeoutMs = 750) {
  return new Promise((resolve) => {
    const sock = net.connect({ host: '127.0.0.1', port: MC_PORT });
    const done = (ok) => {
      sock.removeAllListeners();
      sock.destroy();
      resolve(ok);
    };
    sock.setTimeout(timeoutMs);
    sock.once('connect', () => done(true));
    sock.once('timeout', () => done(false));
    sock.once('error', () => done(false));
  });
}

function tailLog(lines = 50) {
  for (const f of LOG_CANDIDATES) {
    const text = readFileSyncSafe(f);
    if (text) {
      const all = text.split('\n');
      return all.slice(-lines).filter(Boolean).join('\n');
    }
  }
  return '';
}

function rssKbOf(pid) {
  const stat = readFileSyncSafe(`/proc/${pid}/status`);
  if (!stat) return 0;
  const m = stat.match(/^VmRSS:\s+(\d+)\s+kB$/m);
  return m ? parseInt(m[1], 10) : 0;
}

function memInfo() {
  const info = { totalBytes: 0, availableBytes: 0, javaRssBytes: 0 };
  const mi = readFileSyncSafe('/proc/meminfo') || '';
  for (const line of mi.split('\n')) {
    const mm = line.match(/^(MemTotal|MemAvailable):\s+(\d+)\s+kB$/);
    if (!mm) continue;
    const kb = parseInt(mm[2], 10);
    if (mm[1] === 'MemTotal') info.totalBytes = kb * 1024;
    else info.availableBytes = kb * 1024;
  }
  const jpid = findJavaProcess();
  if (jpid) info.javaRssBytes = rssKbOf(jpid) * 1024;
  return info;
}

function fmtBytes(b) {
  if (!Number.isFinite(b) || b <= 0) return '0 MiB';
  if (b >= 1073741824) return `${(b / 1073741824).toFixed(2)} GiB`;
  return `${Math.round(b / 1048576)} MiB`;
}

const CLAIM_FILE = path.join(APP_DIR, 'CLAIM_URL');
const ADDR_FILE = path.join(APP_DIR, 'PLAYIT_ADDRESS');

/** playit.gg agent status. The agent runs from first boot in one of two modes:
    claimed (PLAYIT_SECRET_KEY present) or claim mode, where it prints a
    https://playit.gg/claim/<code> URL that tunnel.sh copies into CLAIM_URL.
    Once tunneled, the assigned host:port lands in PLAYIT_ADDRESS. */
function tunnelState() {
  const hasEnvKey = Boolean(process.env.PLAYIT_SECRET_KEY);
  const secretPath = path.join(SERVER_DIR, 'playit.secret.toml');
  const backupPath = path.join(APP_DIR, 'config', 'playit-secret.backup.toml');
  const statSize = (p) => {
    try { return fs.statSync(p).size; } catch (_) { return 0; }
  };
  // Any saved secret means claimed-for-good: claim links stay retired.
  const secretHeld = hasEnvKey || statSize(secretPath) > 0 || statSize(backupPath) > 0;
  let running = false;
  try {
    const pidFile = readFileSyncSafe(path.join(APP_DIR, 'playit.pid'));
    if (pidFile && pidFile.trim()) running = isNodeRunning(parseInt(pidFile.trim(), 10));
  } catch (_) { running = false; }
  const claimUrl = secretHeld ? null : ((readFileSyncSafe(CLAIM_FILE) || '').trim() || null);
  const address = (readFileSyncSafe(ADDR_FILE) || '').trim() || null;
  // "host:port" -> host. Java MC resolves an SRV record on bare hostnames,
  // so the dashboard offers both spellings; bare-IP strings get RST by the edge.
  const addressHost =
    address && address.includes(':')
      ? address.slice(0, address.lastIndexOf(':'))
      : null;
  return {
    hasSecret: secretHeld,
    claimPending: !secretHeld && Boolean(claimUrl),
    running,
    claimUrl,
    address,
    addressHost,
    configured: Boolean(address),
  };
}

async function serverState() {
  const jpid = findJavaProcess();
  if (!jpid) return { state: 'stopped', pid: 0, minecraftReachable: false };

  // Only declare running when java holds the game port, mirroring the brief.
  const reachable = await probeMcPort();
  return {
    state: reachable ? 'running' : 'starting',
    pid: jpid,
    minecraftReachable: reachable,
  };
}

function runScript(scriptName, label, res) {
  const child = spawnSync('bash', [path.join(APP_DIR, 'scripts', scriptName)], {
    cwd: APP_DIR,
    encoding: 'utf8',
    timeout: 60000,
  });
  const out = `${child.stdout || ''}${child.stderr || ''}`.trim();
  res.json({ ok: child.status === 0, label, output: out.slice(-4000) });
}

// ---------- routes ----------

app.get('/health', (_req, res) => res.json({ status: 'ok' }));

// Dashboard app: negotiated so CLI checks get machine-readable JSON while any
// normal browser still gets the full UI.
app.get('/', async (req, res) => {
  const accept = String(req.headers.accept || '');
  if (!accept.includes('text/html')) {
    const st = await serverState();
    const t = tunnelState();
    return res.json({
      status: st.state,
      pid: st.pid,
      tunnel_configured: t.configured,
      tunnel_address: t.address,
      claim_url: t.claimUrl,
      claim_pending: t.claimPending,
      playit_running: t.running,
      minecraft_port_open: st.minecraftReachable,
      ram: memInfo(),
    });
  }
  res.type('html').send(dashboard.page(MC_PORT, tunnelState()));
});

app.get('/api/status', async (_req, res) => {
  const st = await serverState();
  const t = tunnelState();
  res.json({
    status: st.state,
    pid: st.pid,
    minecraft_port_open: st.minecraftReachable,
    playit_running: t.running,
    claim_pending: t.claimPending,
    playit_claim_url: t.claimUrl,
    playit_address: t.address,
    tunnel_configured: t.configured,
    tunnel_address: t.address,
    version: (readFileSyncSafe(path.join(SERVER_DIR, 'paper.version')) || '').trim(),
    ram_total_human: fmtBytes(memInfo().totalBytes),
    ram_used_human: fmtBytes(Math.max(memInfo().totalBytes - memInfo().availableBytes, 0)),
    ram_java_human: fmtBytes(memInfo().javaRssBytes),
    log_lines: tailLog(50).split('\n').reverse(), // newest-first
  });
});

// Zip of the whole project source, generated fresh on every request so the
// download always reflects the current code. Reading + deflating stays under
// a couple of MB once heavy dirs are excluded, so it is done synchronously.
app.get('/api/export-project', (_req, res) => {
  try {
    const zip = projectExport.buildZip();
    res
      .status(200)
      .type('application/zip')
      .set('Content-Disposition', `attachment; filename="${projectExport.exportFilename()}"`)
      .set('Cache-Control', 'no-store')
      .send(zip);
  } catch (err) {
    res.status(500).json({ error: `export failed: ${err.message}` });
  }
});

app.post('/control/:action', (req, res) => {
  const action = String(req.params.action);
  const map = { start: 'launch-server.sh', stop: 'stop-server.sh', restart: null };
  if (!(action in map)) return res.status(400).json({ error: 'unknown action' });
  if (action === 'restart') {
    spawnSync('bash', [path.join(APP_DIR, 'scripts', 'stop-server.sh')],
      { cwd: APP_DIR, encoding: 'utf8', timeout: 90000 });
    return runScript('launch-server.sh', 'restart', res);
  }
  return runScript(map[action], action, res);
});

app.post('/command', (req, res) => {
  const command = req.body && req.body.command;
  if (typeof command !== 'string' || !command.trim()) {
    return res.status(400).json({ error: 'body {"command": "..."} required' });
  }
  if (!findJavaProcess()) return res.status(409).json({ error: 'server not running' });
  try {
    fs.appendFileSync(CMDS_FILE, `${command.trim().replace(/\r?\n/g, ' ')}\n`);
    res.json({ ok: true, sent: command.trim() });
  } catch (err) {
    res.status(500).json({ error: `append failed: ${err.message}` });
  }
});

// Prevent shutdown leaks if the module ever gets reused in tests.
const server = app.listen(parseInt(process.env.PORT || '3000', 10), '0.0.0.0', () =>
  console.log(`[webapp] dashboard on 0.0.0.0:${process.env.PORT || 3000}`));

module.exports = app;
if (require.main === module) {
  console.log('[webapp] standalone mode, listening');
}
process.on('SIGTERM', () => server.close(() => process.exit(0)));
