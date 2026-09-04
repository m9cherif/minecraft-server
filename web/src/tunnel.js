'use strict';

// WebSocket -> TCP relay.
//
// Minecraft speaks raw TCP. Web hosting — Hostinger's included — proxies HTTP
// and WebSocket on one port and nothing else. So a player's client runs
// bin/mc-tunnel-client.js, which listens on 127.0.0.1:25565, wraps that TCP
// stream in a WebSocket, and this handler unwraps it and connects onward to the
// real server. It is the same idea as the wstunnel setup in deploy/RENDER.md,
// written in Node so it needs no extra binary on the host.
//
// Two limits keep the public endpoint from becoming an open proxy:
//   * the destination is fixed by configuration; the client never names it, and
//   * the URL path must carry a secret, so a scanner of the domain gets a 404.

const net = require('net');
const crypto = require('crypto');
const { OPCODE, acceptValue, encodeFrame, encodeClose, FrameParser } = require('./ws');

// Compare without leaking the answer through timing. Lengths are compared
// first, which is unavoidable and not sensitive.
function secretMatches(given, expected) {
  const a = Buffer.from(given, 'utf8');
  const b = Buffer.from(expected, 'utf8');
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function refuse(socket, status, message) {
  const body = `${message}\n`;
  socket.end(
    `HTTP/1.1 ${status}\r\n` +
      'Connection: close\r\n' +
      'Content-Type: text/plain; charset=utf-8\r\n' +
      `Content-Length: ${Buffer.byteLength(body)}\r\n` +
      '\r\n' +
      body
  );
}

/**
 * Attach the tunnel to an http.Server's `upgrade` event.
 * @returns {{ stats: () => {active: number, total: number} }}
 */
function attachTunnel(server, config, log = console) {
  const { secret, targetHost, targetPort, maxClients, idleTimeoutMs } = config;
  let active = 0;
  let total = 0;

  server.on('upgrade', (req, socket, head) => {
    socket.on('error', () => socket.destroy()); // Before anything else can throw.

    const first = (req.url || '/').split('?')[0].split('/')[1] || '';
    if (!secretMatches(first, secret)) {
      // Same answer a wrong URL would get anywhere else on the site.
      return refuse(socket, '404 Not Found', 'Not found');
    }
    if (String(req.headers.upgrade || '').toLowerCase() !== 'websocket') {
      return refuse(socket, '400 Bad Request', 'Expected a WebSocket upgrade');
    }
    if (req.headers['sec-websocket-version'] !== '13') {
      return refuse(socket, '426 Upgrade Required', 'WebSocket version 13 is required');
    }
    const key = req.headers['sec-websocket-key'];
    if (!key) {
      return refuse(socket, '400 Bad Request', 'Missing Sec-WebSocket-Key');
    }
    if (active >= maxClients) {
      return refuse(socket, '503 Service Unavailable', 'Tunnel is at capacity');
    }

    socket.write(
      'HTTP/1.1 101 Switching Protocols\r\n' +
        'Upgrade: websocket\r\n' +
        'Connection: Upgrade\r\n' +
        `Sec-WebSocket-Accept: ${acceptValue(key)}\r\n` +
        '\r\n'
    );

    active += 1;
    total += 1;
    const id = total;
    const upstream = net.createConnection({ host: targetHost, port: targetPort });
    let closed = false;

    const close = (reason) => {
      if (closed) return;
      closed = true;
      active -= 1;
      log.info?.(`[tunnel] #${id} closed (${reason}) — ${active} active`);
      try {
        socket.write(encodeClose(1000, ''));
      } catch {
        /* the socket may already be gone */
      }
      socket.destroy();
      upstream.destroy();
    };

    socket.setNoDelay(true);
    upstream.setNoDelay(true);
    socket.setTimeout(idleTimeoutMs, () => close('idle'));
    upstream.setTimeout(idleTimeoutMs, () => close('upstream idle'));

    const parser = new FrameParser({
      requireMask: true, // Everything a client sends must be masked.
      onError: (err) => close(`protocol error: ${err.message}`),
      onFrame: ({ opcode, payload }) => {
        switch (opcode) {
          case OPCODE.CONTINUATION:
          case OPCODE.TEXT:
          case OPCODE.BINARY:
            // The relay carries a byte stream, so message boundaries do not
            // matter — write the payload straight through, and pause the
            // WebSocket side whenever the game server stops draining.
            if (!upstream.write(payload)) socket.pause();
            break;
          case OPCODE.PING:
            socket.write(encodeFrame(OPCODE.PONG, payload));
            break;
          case OPCODE.PONG:
            break;
          case OPCODE.CLOSE:
            close('client closed');
            break;
          default:
            close(`unknown opcode 0x${opcode.toString(16)}`);
        }
      },
    });

    // `head` is whatever arrived in the same packet as the upgrade request, so
    // it is the first of the stream and must reach the parser before anything
    // the 'data' handler below delivers. Pushing it here — synchronously,
    // before that handler exists — is what guarantees the order: doing it from
    // upstream's 'connect' callback instead leaves a window in which a client
    // that writes immediately after the request gets its bytes parsed first,
    // silently corrupting the stream. Writing to a still-connecting socket is
    // safe; Node buffers until the connection is up.
    if (head && head.length) parser.push(head);

    upstream.on('connect', () => {
      log.info?.(`[tunnel] #${id} open -> ${targetHost}:${targetPort} — ${active} active`);
    });

    upstream.on('drain', () => socket.resume());
    socket.on('drain', () => upstream.resume());

    socket.on('data', (chunk) => parser.push(chunk));
    upstream.on('data', (chunk) => {
      if (!socket.write(encodeFrame(OPCODE.BINARY, chunk))) upstream.pause();
    });

    socket.on('close', () => close('websocket closed'));
    upstream.on('close', () => close('server closed'));
    upstream.on('error', (err) => close(`server unreachable: ${err.message}`));
    socket.on('error', (err) => close(`socket error: ${err.message}`));
  });

  return { stats: () => ({ active, total }) };
}

module.exports = { attachTunnel };
