'use strict';

// Integration tests. No framework: `node test/run.js`, exit code says the rest.
// They cover the three things that can actually break — the ping parser, the
// HTTP routes, and the tunnel carrying bytes both ways.

const assert = require('assert');
const net = require('net');
const { execFile } = require('child_process');
const { EventEmitter } = require('events');
const path = require('path');

const { pingServer } = require('../src/ping');
const { encodeFrame, FrameParser, OPCODE, acceptValue } = require('../src/ws');
const { createServer } = require('../src/server');
const { attachTunnel } = require('../src/tunnel');
const { startFakeMinecraft } = require('./fake-minecraft');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

const listen = (server) =>
  new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server.address().port)));
const close = (server) => new Promise((resolve) => server.close(resolve));

const quietLog = { info: () => {}, error: () => {} };

function baseConfig(overrides = {}) {
  return {
    port: 0,
    host: '127.0.0.1',
    mc: { host: '127.0.0.1', port: 25565, address: 'play.example.com', name: 'Test' },
    status: { cacheMs: 10000, timeoutMs: 2000 },
    tunnel: {
      enabled: false,
      secret: '',
      targetHost: '',
      targetPort: 0,
      maxClients: 10,
      idleTimeoutMs: 5000,
    },
    trustProxy: true,
    tunnelErrors: [],
    ...overrides,
  };
}

const STATUS = {
  version: { name: 'Fabric 26.2', protocol: 800 },
  players: { online: 3, max: 100, sample: [{ name: 'alice' }, { name: 'bob' }] },
  description: { text: '§aA test ', extra: [{ text: 'server' }] },
};

// ------------------------------------------------------------------ ping

test('ping reads version, players and MOTD', async () => {
  const fake = await startFakeMinecraft(STATUS);
  const result = await pingServer({ host: '127.0.0.1', port: fake.port, timeoutMs: 2000 });
  await fake.close();

  assert.equal(result.online, true);
  assert.equal(result.version, 'Fabric 26.2');
  assert.equal(result.players.online, 3);
  assert.equal(result.players.max, 100);
  assert.deepEqual(result.players.sample, ['alice', 'bob']);
  assert.equal(result.motd, 'A test server', 'colour codes are stripped, extra is joined');
  assert.ok(typeof result.latencyMs === 'number');
});

test('ping reports an unreachable server instead of throwing', async () => {
  // Port 1 on loopback: nothing listens, so the connection is refused at once.
  const result = await pingServer({ host: '127.0.0.1', port: 1, timeoutMs: 1000 });
  assert.equal(result.online, false);
  assert.ok(result.error, 'an error message is included');
});

// ------------------------------------------------------------------ http

async function withApp(config, fn) {
  const server = createServer(config, quietLog);
  const port = await listen(server);
  try {
    await fn(port, server);
  } finally {
    await close(server);
  }
}

const get = async (port, route) => {
  const res = await fetch(`http://127.0.0.1:${port}${route}`);
  const text = await res.text();
  return { status: res.status, headers: res.headers, text };
};

test('the status page renders and the API answers', async () => {
  const fake = await startFakeMinecraft(STATUS);
  const config = baseConfig();
  config.mc.port = fake.port;

  await withApp(config, async (port) => {
    const page = await get(port, '/');
    assert.equal(page.status, 200);
    assert.match(page.headers.get('content-type'), /text\/html/);
    assert.ok(page.text.includes('play.example.com'), 'the join address is on the page');

    const api = await get(port, '/api/status');
    assert.equal(api.status, 200);
    const body = JSON.parse(api.text);
    assert.equal(body.online, true);
    assert.equal(body.players.online, 3);

    // Second call inside the cache window must not re-ping.
    const again = JSON.parse((await get(port, '/api/status')).text);
    assert.equal(again.cached, true);

    const health = JSON.parse((await get(port, '/healthz')).text);
    assert.equal(health.ok, true);
    assert.equal(health.tunnel.enabled, false);

    assert.equal((await get(port, '/nope')).status, 404);

    const posted = await fetch(`http://127.0.0.1:${port}/`, { method: 'POST' });
    assert.equal(posted.status, 405);
  });

  await fake.close();
});

test('routes still match when the app is mounted under a subdirectory', async () => {
  const fake = await startFakeMinecraft(STATUS);
  const config = baseConfig();
  config.mc.port = fake.port;

  await withApp(config, async (port) => {
    const body = JSON.parse((await get(port, '/minecraft/api/status')).text);
    assert.equal(body.online, true);
  });

  await fake.close();
});

// ---------------------------------------------------------------- tunnel

/** Minimal WebSocket client: resolves once the handshake is done. */
function connectWebSocket(port, urlPath) {
  return new Promise((resolve, reject) => {
    const key = Buffer.from('0123456789abcdef').toString('base64');
    const socket = net.createConnection({ host: '127.0.0.1', port }, () => {
      socket.write(
        `GET ${urlPath} HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n` +
          `Connection: Upgrade\r\nSec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`
      );
    });
    let head = Buffer.alloc(0);
    const onData = (chunk) => {
      head = Buffer.concat([head, chunk]);
      const end = head.indexOf('\r\n\r\n');
      if (end === -1) return;
      socket.removeListener('data', onData);
      const text = head.subarray(0, end).toString('latin1');
      if (!/^HTTP\/1\.1 101/.test(text)) {
        socket.destroy();
        return reject(new Error(text.split('\r\n')[0]));
      }
      assert.ok(text.includes(acceptValue(key)), 'accept header matches the key');
      resolve({ socket, rest: head.subarray(end + 4) });
    };
    socket.on('data', onData);
    socket.on('error', reject);
    // A refused upgrade may just be a hang-up, with no error and no response.
    socket.on('close', () => reject(new Error('closed without completing the handshake')));
  });
}

test('the tunnel carries bytes to the target and back', async () => {
  // Stand-in for Minecraft: uppercases whatever it receives.
  const upstream = net.createServer((socket) =>
    socket.on('data', (chunk) => socket.write(Buffer.from(chunk.toString().toUpperCase())))
  );
  const upstreamPort = await listen(upstream);

  const config = baseConfig();
  config.tunnel = {
    enabled: true,
    secret: 'a-secret-long-enough',
    targetHost: '127.0.0.1',
    targetPort: upstreamPort,
    maxClients: 10,
    idleTimeoutMs: 5000,
  };

  await withApp(config, async (port) => {
    const { socket, rest } = await connectWebSocket(port, '/a-secret-long-enough');
    const received = [];
    const parser = new FrameParser({
      requireMask: false,
      onFrame: ({ payload }) => received.push(payload),
      onError: (err) => {
        throw err;
      },
    });
    if (rest.length) parser.push(rest);
    socket.on('data', (chunk) => parser.push(chunk));

    socket.write(encodeFrame(OPCODE.BINARY, Buffer.from('hello tunnel'), true));

    await new Promise((resolve) => {
      const started = Date.now();
      const poll = setInterval(() => {
        if (received.length || Date.now() - started > 3000) {
          clearInterval(poll);
          resolve();
        }
      }, 20);
    });

    assert.equal(Buffer.concat(received).toString(), 'HELLO TUNNEL');
    socket.destroy();
  });

  await close(upstream);
});

test('the tunnel delivers bytes sent in the upgrade packet, in order', async () => {
  // A client may pipeline: its first WebSocket frame rides in the same packet
  // as the GET, so Node hands it over as `head` rather than a 'data' event.
  // Those bytes start the stream — delivered after later ones, the connection
  // is corrupt in a way that looks like a Minecraft bug, not a tunnel bug.
  //
  // Driving the upgrade by hand rather than over a socket is what makes this
  // deterministic: against a real localhost upstream the connection completes
  // before a second write can arrive, so the ordering never gets tested. Here
  // the later bytes are delivered synchronously, while upstream is still
  // connecting — exactly the window the bug lives in.
  const seen = [];
  const upstream = net.createServer((s) => s.on('data', (c) => seen.push(c)));
  const upstreamPort = await listen(upstream);

  const server = new EventEmitter();
  attachTunnel(
    server,
    {
      secret: 'a-secret-long-enough',
      targetHost: '127.0.0.1',
      targetPort: upstreamPort,
      maxClients: 10,
      idleTimeoutMs: 5000,
    },
    quietLog
  );

  // Minimal stand-in for the upgraded socket: the tunnel only reads from it
  // through events and writes back frames we do not assert on here.
  const socket = new EventEmitter();
  Object.assign(socket, {
    write: () => true,
    destroy() { socket.emit('close'); },
    end() { socket.emit('close'); },
    pause() {}, resume() {}, setNoDelay() {}, setTimeout() {},
  });

  const req = {
    url: '/a-secret-long-enough',
    headers: {
      upgrade: 'websocket',
      'sec-websocket-version': '13',
      'sec-websocket-key': Buffer.from('0123456789abcdef').toString('base64'),
    },
  };

  server.emit('upgrade', req, socket, encodeFrame(OPCODE.BINARY, Buffer.from('FIRST'), true));
  // Still inside the same tick — upstream cannot have connected yet.
  socket.emit('data', encodeFrame(OPCODE.BINARY, Buffer.from('SECOND'), true));

  await new Promise((resolve) => {
    const started = Date.now();
    const poll = setInterval(() => {
      if (Buffer.concat(seen).toString().length >= 11 || Date.now() - started > 3000) {
        clearInterval(poll);
        resolve();
      }
    }, 20);
  });

  socket.destroy();
  await close(upstream);
  assert.equal(Buffer.concat(seen).toString(), 'FIRSTSECOND', 'head must arrive before later data');
});

test('the tunnel refuses a wrong secret with a plain 404', async () => {
  const config = baseConfig();
  config.tunnel = {
    enabled: true,
    secret: 'a-secret-long-enough',
    targetHost: '127.0.0.1',
    targetPort: 1,
    maxClients: 10,
    idleTimeoutMs: 5000,
  };

  await withApp(config, async (port) => {
    await assert.rejects(
      () => connectWebSocket(port, '/wrong-secret-entirely'),
      /404/,
      'a scanner learns nothing beyond "not found"'
    );
  });
});

test('upgrades are dropped outright when the tunnel is off', async () => {
  await withApp(baseConfig(), async (port) => {
    await assert.rejects(() => connectWebSocket(port, '/anything'));
  });
});

// ------------------------------------------------------------------- cli

test('mc-ping reports a reachable server and exits 0', async () => {
  const fake = await startFakeMinecraft(STATUS);
  const script = path.join(__dirname, '..', 'bin', 'mc-ping.js');
  const { code, stdout } = await new Promise((resolve) => {
    execFile(process.execPath, [script, `127.0.0.1:${fake.port}`], (err, out) =>
      resolve({ code: err ? err.code : 0, stdout: out })
    );
  });
  await fake.close();

  assert.equal(code, 0, 'exit 0 means a player would get in');
  assert.match(stdout, /ONLINE/);
  assert.match(stdout, /3\/100 players/);
});

test('mc-ping exits 1 when nothing answers', async () => {
  const script = path.join(__dirname, '..', 'bin', 'mc-ping.js');
  const code = await new Promise((resolve) => {
    // Port 1 on loopback: refused immediately, no waiting.
    execFile(process.execPath, [script, '127.0.0.1:1'], (err) => resolve(err ? err.code : 0));
  });
  assert.equal(code, 1, 'usable as a health check');
});

test('the tunnel client prints usage and exits 1 on bad arguments', async () => {
  const script = path.join(__dirname, '..', 'bin', 'mc-tunnel-client.js');
  const code = await new Promise((resolve) => {
    execFile(process.execPath, [script, 'http://not-a-websocket-url'], (err) =>
      resolve(err ? err.code : 0)
    );
  });
  assert.equal(code, 1);
});

// ------------------------------------------------------------------- run

(async () => {
  let failed = 0;
  for (const { name, fn } of tests) {
    try {
      await fn();
      console.log(`  ok   ${name}`);
    } catch (err) {
      failed++;
      console.error(`  FAIL ${name}`);
      console.error(`       ${err.message}`);
    }
  }
  console.log(`\n${tests.length - failed}/${tests.length} passed`);
  process.exit(failed ? 1 : 0);
})();
