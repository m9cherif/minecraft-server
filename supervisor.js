#!/usr/bin/env node
/**
 * Baarcha MC supervisor.
 *
 * Boots the Minecraft stack (Paper server + playit.gg tunnel) and the Express
 * dashboard. `npm start` and `node server.js` both land here.
 *
 * Order matters: the web app binds first so the platform's /health probe sees
 * a healthy listener even while first-run provisioning (JRE/Paper download)
 * is still going on in the background.
 */
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const APP_DIR = __dirname;
const SCRIPTS = path.join(APP_DIR, 'scripts');
const SERVER_DIR = path.join(APP_DIR, 'server');
const PID_FILE = path.join(SERVER_DIR, 'java.pid');
const PLAYIT_PID_FILE = path.join(APP_DIR, 'playit.pid');
const WATCHDOG_MS = 5000;
const RELAUNCH_COOLDOWN_MS = 20000;

let lastJavaRelaunch = 0;
let lastTunnelRelaunch = 0;

function log(msg) {
  console.log(`[supervisor] ${msg}`);
}

/** True when the recorded pid's cmdline still matches `needle`. */
function pidAlive(pidFile, needle) {
  try {
    const pid = parseInt(fs.readFileSync(pidFile, 'utf8').trim(), 10);
    if (!Number.isFinite(pid) || pid <= 0) return false;
    const cmd = fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8');
    // cmdline is NUL-separated; match against readable text.
    return cmd.replace(/\0/g, ' ').includes(needle);
  } catch (_) {
    return false;
  }
}

const isServerAlive = () => pidAlive(PID_FILE, 'paper.jar');
const isTunnelAlive = () => pidAlive(PLAYIT_PID_FILE, 'playit');

function run(script, label) {
  return new Promise((resolve) => {
    const child = spawn('bash', [script], {
      cwd: APP_DIR,
      stdio: ['ignore', 'inherit', 'inherit'],
      env: process.env,
    });
    child.on('exit', (code, signal) => {
      log(`${label} exited code=${code}${signal ? ` signal=${signal}` : ''}`);
      resolve();
    });
  });
}

async function bootstrapStack() {
  await run(path.join(SCRIPTS, 'setup-server.sh'), 'setup');
  require('./scripts/gen-ops.js').ensureOps(); // ops.json ready before JVM reads it
  if (isServerAlive()) {
    log('pre-existing Paper process detected — skipping launch');
  } else {
    await run(path.join(SCRIPTS, 'launch-server.sh'), 'minecraft-server');
  }
  // The playit agent runs from the first boot, claimed or not: without a
  // PLAYIT_SECRET_KEY it goes into claim mode and prints a claim URL that the
  // dashboard surfaces, so linking the account never needs shell access.
  if (isTunnelAlive()) return;
  // Tunnel waits for java to hold 25565 so it forwards to something.
  setTimeout(() => run(path.join(SCRIPTS, 'tunnel.sh'), 'playit-tunnel'), 2000);
}

/** Keep-alive loop: relaunch crashed children with a cooldown. The playit
    agent is always supervised (claim mode counts as running). */
function watchdog() {
  setInterval(async () => {
    const now = Date.now();
    const hasJar = fs.existsSync(path.join(SERVER_DIR, 'paper.jar'));
    if (hasJar && !isServerAlive() && now - lastJavaRelaunch > RELAUNCH_COOLDOWN_MS) {
      lastJavaRelaunch = now;
      log('watchdog: minecraft down — relaunching');
      await run(path.join(SCRIPTS, 'launch-server.sh'), 'watchdog-minecraft');
    }
    if (!isTunnelAlive() &&
        now - lastTunnelRelaunch > RELAUNCH_COOLDOWN_MS) {
      lastTunnelRelaunch = now;
      log('watchdog: tunnel down — relaunching');
      await run(path.join(SCRIPTS, 'tunnel.sh'), 'watchdog-playit');
    }
  }, WATCHDOG_MS).unref();
}

function start() {
  // 1. Dashboard immediately — endpoint of record for /health probing.
  require('./webapp-dashboard.js');
  log('dashboard listening, starting minecraft stack bootstrap');

  // 2. Provision + launch without blocking the event loop longer than needed.
  watchdog();
  bootstrapStack().catch((err) => log(`bootstrap error: ${err.message}`));
}

if (require.main === module) {
  start();
}
module.exports = { start };
