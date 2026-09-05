#!/usr/bin/env node
'use strict';

// Player-side half of the tunnel.
//
// Run this on the machine you play on, then point Minecraft at
// `localhost:25565` as usual. Each connection Minecraft makes is wrapped in a
// WebSocket and sent to the hosted app, which unwraps it and hands it to the
// real server. Nothing is installed: it is one file and Node's standard library.
//
//   node bin/mc-tunnel-client.js wss://play.example.com/YOUR_TUNNEL_SECRET
//   node bin/mc-tunnel-client.js wss://play.example.com/SECRET --port 25566
//
// Ctrl-C stops it.

const net = require('net');
const tls = require('tls');
const { OPCODE, acceptValue, generateKey, encodeFrame, encodeClose, FrameParser } = require('../src/ws');

const USAGE = `Usage: mc-tunnel-client <wss://host/SECRET> [--port 25565] [--host 127.0.0.1]

  <wss://host/SECRET>   the tunnel URL, secret path included
  --port                local port Minecraft connects to (default 25565)
  --host                local address to bind (default 127.0.0.1)
  --insecure            skip TLS certificate verification (debugging only)
`;

function parseArgs(argv) {
  const options = { port: 25565, host: '127.0.0.1', insecure: false, url: null };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--port') options.port = Number.parseInt(argv[++i], 10);
    else if (arg === '--host') options.host = argv[++i];
    else if (arg === '--insecure') options.insecure = true;
    else if (arg === '-h' || arg === '--help') return 'help';
    else if (!options.url) options.url = arg;
    else return null;
  }
  if (!options.url || !Number.isInteger(options.port)) return null;
  return options;
}

const options = parseArgs(process.argv.slice(2));
if (options === 'help') {
  process.stdout.write(USAGE);
  process.exit(0);
}
if (!options) {
  process.stderr.write(USAGE);
  process.exit(1);
}

let target;
try {
  target = new URL(options.url);
} catch {
  console.error(`Not a valid URL: ${options.url}`);
  process.exit(1);
}
if (target.protocol !== 'ws:' && target.protocol !== 'wss:') {
  console.error(`Expected a ws:// or wss:// URL, got ${target.protocol}//`);
  process.exit(1);
}

const secure = target.protocol === 'wss:';
const remotePort = target.port ? Number(target.port) : secure ? 443 : 80;
const requestPath = (target.pathname || '/') + target.search;

let connectionCount = 0;

/** Open one WebSocket to the tunnel and relay `local` through it. */
function bridge(local) {
  const id = ++connectionCount;
  const key = generateKey();
  let closed = false;
  let handshakeDone = false;
  let headerBuffer = Buffer.alloc(0);

  const remote = secure
    ? tls.connect({
        host: target.hostname,
        port: remotePort,
        servername: target.hostname,
        rejectUnauthorized: !options.insecure,
      })
    : net.createConnection({ host: target.hostname, port: remotePort });

  const close = (reason) => {
    if (closed) return;
    closed = true;
    console.log(`[${id}] closed — ${reason}`);
    if (handshakeDone) {
      try {
        remote.write(encodeClose(1000, '', true));
      } catch {
        /* already gone */
      }
    }
    remote.destroy();
    local.destroy();
  };

  const parser = new FrameParser({
    requireMask: false, // Servers never mask.
    onError: (err) => close(`protocol error: ${err.message}`),
    onFrame: ({ opcode, payload }) => {
      switch (opcode) {
        case OPCODE.CONTINUATION:
        case OPCODE.TEXT:
        case OPCODE.BINARY:
          if (!local.write(payload)) remote.pause();
          break;
        case OPCODE.PING:
          remote.write(encodeFrame(OPCODE.PONG, payload, true));
          break;
        case OPCODE.PONG:
          break;
        case OPCODE.CLOSE:
          close('server closed the tunnel');
          break;
        default:
          close(`unknown opcode 0x${opcode.toString(16)}`);
      }
    },
  });

  // Minecraft may send its handshake before the WebSocket is up; hold those
  // bytes rather than dropping them.
  const pending = [];
  local.pause();

  remote.on(secure ? 'secureConnect' : 'connect', () => {
    remote.setNoDelay(true);
    remote.write(
      `GET ${requestPath} HTTP/1.1\r\n` +
        `Host: ${target.hostname}${target.port ? ':' + target.port : ''}\r\n` +
        'Upgrade: websocket\r\n' +
        'Connection: Upgrade\r\n' +
        `Sec-WebSocket-Key: ${key}\r\n` +
        'Sec-WebSocket-Version: 13\r\n' +
        'User-Agent: mc-tunnel-client\r\n' +
        '\r\n'
    );
  });

  remote.on('data', (chunk) => {
    if (handshakeDone) return parser.push(chunk);

    headerBuffer = Buffer.concat([headerBuffer, chunk]);
    const end = headerBuffer.indexOf('\r\n\r\n');
    if (end === -1) {
      if (headerBuffer.length > 16384) close('server sent an oversized HTTP response');
      return;
    }

    const head = headerBuffer.subarray(0, end).toString('latin1');
    const rest = headerBuffer.subarray(end + 4);
    const statusLine = head.split('\r\n')[0];

    if (!/^HTTP\/1\.1 101/.test(statusLine)) {
      return close(`tunnel refused the connection: ${statusLine.trim() || 'no response'}`);
    }
    const accept = /\r\nsec-websocket-accept:\s*(\S+)/i.exec('\r\n' + head);
    if (!accept || accept[1] !== acceptValue(key)) {
      return close('server failed the WebSocket handshake check');
    }

    handshakeDone = true;
    console.log(`[${id}] connected via ${target.hostname}`);

    for (const buffered of pending.splice(0)) {
      remote.write(encodeFrame(OPCODE.BINARY, buffered, true));
    }
    local.resume();
    if (rest.length) parser.push(rest);
  });

  local.setNoDelay(true);
  local.on('data', (chunk) => {
    if (!handshakeDone) return pending.push(chunk);
    if (!remote.write(encodeFrame(OPCODE.BINARY, chunk, true))) local.pause();
  });

  local.on('drain', () => remote.resume());
  remote.on('drain', () => local.resume());

  local.on('close', () => close('Minecraft disconnected'));
  remote.on('close', () => close('tunnel disconnected'));
  local.on('error', (err) => close(`local error: ${err.message}`));
  remote.on('error', (err) => close(`tunnel error: ${err.message}`));
}

const listener = net.createServer(bridge);

listener.listen(options.port, options.host, () => {
  console.log(`Tunnel client ready.`);
  console.log(`  Minecraft  ->  ${options.host}:${options.port}`);
  console.log(`  forwarded  ->  ${target.protocol}//${target.hostname}:${remotePort}${requestPath}`);
  console.log(`\nAdd a server in Minecraft with the address  ${options.host}:${options.port}`);
  if (options.insecure) console.log('\nWARNING: certificate verification is off (--insecure).');
});

listener.on('error', (err) => {
  if (err.code === 'EADDRINUSE') {
    console.error(`Port ${options.port} is already in use — is another copy running?`);
    process.exit(1);
  }
  throw err;
});

process.on('SIGINT', () => {
  console.log('\nStopping.');
  listener.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 2000).unref();
});
