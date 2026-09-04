'use strict';

// Every setting comes from the environment, because that is the only thing you
// can edit in a Hostinger hPanel Node.js app without redeploying files.
// A `.env` file sitting next to app.js is loaded first, for local runs and for
// hosts that give you a shell but no environment editor.

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');

// Minimal .env reader: KEY=VALUE per line, `#` comments, optional quotes.
// Node 20.6+ has --env-file, but Hostinger's app runner does not let you pass
// node flags, so the file is read here instead. Real environment variables win.
function loadDotEnv(file) {
  let text;
  try {
    text = fs.readFileSync(file, 'utf8');
  } catch {
    return;
  }
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq === -1) continue;
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"') && value.length > 1) ||
      (value.startsWith("'") && value.endsWith("'") && value.length > 1)
    ) {
      value = value.slice(1, -1);
    }
    if (!(key in process.env)) process.env[key] = value;
  }
}

loadDotEnv(path.join(ROOT, '.env'));

function str(name, fallback) {
  const value = process.env[name];
  return value === undefined || value === '' ? fallback : value;
}

function int(name, fallback) {
  const value = process.env[name];
  if (value === undefined || value === '') return fallback;
  const parsed = Number.parseInt(value, 10);
  if (!Number.isInteger(parsed)) {
    throw new Error(`${name} must be an integer, got ${JSON.stringify(value)}`);
  }
  return parsed;
}

function bool(name, fallback) {
  const value = process.env[name];
  if (value === undefined || value === '') return fallback;
  return /^(1|true|yes|on)$/i.test(value.trim());
}

const config = {
  // Hostinger (and every other Node host) hands the port over in $PORT.
  // HOST stays on all interfaces; the platform decides what is reachable.
  port: int('PORT', 3000),
  host: str('HOST', '0.0.0.0'),

  // The Minecraft server this app reports on. Where it actually runs is
  // unrelated to where this app runs — status is a plain outbound TCP query.
  mc: {
    host: str('MC_HOST', '127.0.0.1'),
    port: int('MC_PORT', 25565),
    // Shown to players. Defaults to host:port, minus the port when it is 25565.
    address: str('MC_ADDRESS', ''),
    name: str('MC_NAME', 'Minecraft'),
  },

  status: {
    // How long a ping result is reused. Keeps a refreshing page (or a bot) from
    // opening a TCP connection to the game server on every request.
    cacheMs: int('STATUS_CACHE_MS', 10000),
    timeoutMs: int('STATUS_TIMEOUT_MS', 5000),
  },

  // WebSocket -> TCP tunnel. Off unless you deliberately turn it on, because it
  // makes this app forward bytes to another machine. See deploy/HOSTINGER.md.
  tunnel: {
    enabled: bool('TUNNEL_ENABLED', false),
    // Secret first path segment. Without the right one the upgrade is refused,
    // so the public URL leaks nothing to a scanner.
    secret: str('TUNNEL_SECRET', ''),
    // The one destination the tunnel may reach. Hardcoding it here is what
    // keeps the endpoint from being usable as a general-purpose proxy.
    targetHost: str('TUNNEL_TARGET_HOST', ''),
    targetPort: int('TUNNEL_TARGET_PORT', 25565),
    maxClients: int('TUNNEL_MAX_CLIENTS', 100),
    // Drop a tunnel connection that has passed no data for this long.
    idleTimeoutMs: int('TUNNEL_IDLE_TIMEOUT_MS', 300000),
  },

  // Trust X-Forwarded-For. True on Hostinger and any other host that puts a
  // reverse proxy in front of the app; false when the app is directly exposed,
  // where a client could otherwise forge the header.
  trustProxy: bool('TRUST_PROXY', true),
};

if (!config.mc.address) {
  config.mc.address =
    config.mc.port === 25565 ? config.mc.host : `${config.mc.host}:${config.mc.port}`;
}

// Fail at startup rather than at the first player's connection attempt.
const tunnelErrors = [];
if (config.tunnel.enabled) {
  if (!config.tunnel.secret || config.tunnel.secret.length < 16) {
    tunnelErrors.push('TUNNEL_SECRET must be set to at least 16 characters');
  }
  if (/[^A-Za-z0-9._~-]/.test(config.tunnel.secret)) {
    tunnelErrors.push('TUNNEL_SECRET must be URL-safe (A-Z a-z 0-9 - . _ ~)');
  }
  if (!config.tunnel.targetHost) {
    tunnelErrors.push('TUNNEL_TARGET_HOST must name the machine running Minecraft');
  }
}
config.tunnelErrors = tunnelErrors;

module.exports = config;
