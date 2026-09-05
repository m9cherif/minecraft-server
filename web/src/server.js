'use strict';

const http = require('http');
const { renderPage } = require('./page');
const { createStatusService } = require('./status');
const { attachTunnel } = require('./tunnel');

function send(res, status, body, headers = {}) {
  const payload = Buffer.isBuffer(body) ? body : Buffer.from(body);
  res.writeHead(status, {
    'content-length': payload.length,
    // The page embeds its own styles and script and loads nothing else, so the
    // policy can be this tight.
    'content-security-policy':
      "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    'x-content-type-options': 'nosniff',
    'referrer-policy': 'no-referrer',
    ...headers,
  });
  res.end(payload);
}

function json(res, status, value) {
  send(res, status, JSON.stringify(value, null, 2) + '\n', {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    // Anyone may read the status; it is the same thing the multiplayer list shows.
    'access-control-allow-origin': '*',
  });
}

/**
 * Build the HTTP server. Returned unstarted so tests can listen on port 0.
 */
function createServer(config, log = console) {
  const status = createStatusService({
    host: config.mc.host,
    port: config.mc.port,
    cacheMs: config.status.cacheMs,
    timeoutMs: config.status.timeoutMs,
  });

  let tunnel = null;
  const page = renderPage(config);

  const server = http.createServer(async (req, res) => {
    // Hostinger mounts the app behind a reverse proxy, and it may sit under a
    // subdirectory (`/status/` rather than `/`). Matching the end of the path
    // rather than the whole of it makes one build work at either mount point.
    const url = new URL(req.url, 'http://localhost');
    const path = url.pathname.replace(/\/+$/, '') || '/';
    const at = (route) => path === route || path.endsWith(route);

    if (req.method !== 'GET' && req.method !== 'HEAD') {
      return json(res, 405, { error: 'Only GET is supported' });
    }

    if (at('/api/status')) {
      const result = await status.get({ force: url.searchParams.get('force') === '1' });
      return json(res, 200, result);
    }

    if (at('/healthz') || at('/health')) {
      return json(res, 200, {
        ok: true,
        uptimeSeconds: Math.round(process.uptime()),
        tunnel: tunnel ? { enabled: true, ...tunnel.stats() } : { enabled: false },
      });
    }

    if (path === '/' || at('/index.html')) {
      return send(res, 200, page, {
        'content-type': 'text/html; charset=utf-8',
        'cache-control': 'no-cache',
      });
    }

    return json(res, 404, { error: 'Not found' });
  });

  if (config.tunnel.enabled) {
    tunnel = attachTunnel(server, config.tunnel, log);
  } else {
    // Without a tunnel, refuse upgrades outright instead of leaving sockets
    // half-open until they time out.
    server.on('upgrade', (_req, socket) => socket.destroy());
  }

  return server;
}

module.exports = { createServer };
