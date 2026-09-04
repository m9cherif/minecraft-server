'use strict';

// Minecraft "Server List Ping" over TCP, as the multiplayer screen does it.
// Protocol reference: https://minecraft.wiki/w/Java_Edition_protocol
//
//   -> Handshake       (packet 0x00, next state = 1)
//   -> Status request  (packet 0x00, empty)
//   <- Status response (packet 0x00, one JSON string)
//   -> Ping            (packet 0x01, an arbitrary long)
//   <- Pong            (packet 0x01, the same long)  -- this is the latency
//
// Every packet is `VarInt length | VarInt id | payload`, and strings are
// `VarInt byte-length | UTF-8`. No dependencies: it is a few dozen lines of
// buffer work, and adding a package for it would be the larger cost.

const net = require('net');

const MAX_PACKET_BYTES = 2 * 1024 * 1024; // A status response is ~KBs; this is a sanity ceiling.

function writeVarInt(value) {
  const bytes = [];
  let remaining = value >>> 0; // Status ping never needs negative VarInts.
  do {
    let byte = remaining & 0x7f;
    remaining >>>= 7;
    if (remaining !== 0) byte |= 0x80;
    bytes.push(byte);
  } while (remaining !== 0);
  return Buffer.from(bytes);
}

// Reads a VarInt at `offset`. Returns null when the buffer does not hold a
// complete one yet, so the caller can wait for more bytes.
function readVarInt(buffer, offset) {
  let value = 0;
  let shift = 0;
  let cursor = offset;
  while (cursor < buffer.length) {
    const byte = buffer[cursor++];
    value |= (byte & 0x7f) << shift;
    if ((byte & 0x80) === 0) return { value: value >>> 0, size: cursor - offset };
    shift += 7;
    if (shift > 35) throw new Error('VarInt is too long');
  }
  return null;
}

function writeString(text) {
  const encoded = Buffer.from(text, 'utf8');
  return Buffer.concat([writeVarInt(encoded.length), encoded]);
}

function packet(id, ...parts) {
  const body = Buffer.concat([writeVarInt(id), ...parts]);
  return Buffer.concat([writeVarInt(body.length), body]);
}

// A MOTD is either a legacy string or a chat component tree. Flatten both to
// plain text and drop the § colour codes, which are noise outside the game.
function flattenChat(node) {
  if (node == null) return '';
  if (typeof node === 'string') return node;
  if (Array.isArray(node)) return node.map(flattenChat).join('');
  let out = typeof node.text === 'string' ? node.text : '';
  if (Array.isArray(node.extra)) out += node.extra.map(flattenChat).join('');
  return out;
}

function stripFormatting(text) {
  return text.replace(/§[0-9a-fk-or]/gi, '');
}

/**
 * Ping a Minecraft server and describe what answered.
 * Never throws: an unreachable server is a result (`online: false`), not an error.
 *
 * @returns {Promise<{online: boolean, error?: string, latencyMs?: number, ...}>}
 */
function pingServer({ host, port = 25565, timeoutMs = 5000 } = {}) {
  return new Promise((resolve) => {
    const startedAt = Date.now();
    let settled = false;
    let received = Buffer.alloc(0);
    let statusJson = null;

    const socket = net.createConnection({ host, port });
    socket.setTimeout(timeoutMs);

    const finish = (result) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      resolve(result);
    };

    const fail = (message) =>
      finish({ online: false, error: message, host, port, checkedAt: new Date().toISOString() });

    socket.on('connect', () => {
      // Protocol version -1 means "I am not telling you", which every server
      // accepts for a status query and keeps this working across game versions.
      const handshake = packet(
        0x00,
        writeVarInt(0xffffffff),
        writeString(host),
        (() => {
          const portBytes = Buffer.alloc(2);
          portBytes.writeUInt16BE(port);
          return portBytes;
        })(),
        writeVarInt(1)
      );
      socket.write(Buffer.concat([handshake, packet(0x00)]));
    });

    socket.on('data', (chunk) => {
      received = Buffer.concat([received, chunk]);

      // One `data` event can carry several packets, or half of one.
      for (;;) {
        let header;
        try {
          header = readVarInt(received, 0);
        } catch (err) {
          return fail(err.message);
        }
        if (!header) return; // Length prefix not complete yet.

        const total = header.size + header.value;
        if (header.value > MAX_PACKET_BYTES) return fail('Server sent an oversized packet');
        if (received.length < total) return; // Body not complete yet.

        const body = received.subarray(header.size, total);
        received = received.subarray(total);

        let id;
        try {
          id = readVarInt(body, 0);
        } catch (err) {
          return fail(err.message);
        }
        if (!id) return fail('Server sent a malformed packet');

        if (id.value === 0x00 && statusJson === null) {
          let length;
          try {
            length = readVarInt(body, id.size);
          } catch (err) {
            return fail(err.message);
          }
          if (!length) return fail('Server sent a truncated status response');
          const start = id.size + length.size;
          const text = body.subarray(start, start + length.value).toString('utf8');
          try {
            statusJson = JSON.parse(text);
          } catch {
            return fail('Server sent a status response that is not JSON');
          }
          // Now time a round trip. Some servers close right after the status
          // response, so the pong is a bonus rather than something to wait on.
          const payload = Buffer.alloc(8);
          payload.writeBigInt64BE(BigInt(Date.now()));
          socket.write(packet(0x01, payload));
        } else if (id.value === 0x01) {
          return finish(describe(statusJson, Date.now() - startedAt));
        }
      }
    });

    // A server that hangs up after the status response still gave us an answer.
    socket.on('close', () => {
      if (statusJson) finish(describe(statusJson, Date.now() - startedAt));
      else fail('Connection closed before the server answered');
    });

    socket.on('timeout', () => fail(`No answer within ${timeoutMs} ms`));
    socket.on('error', (err) => fail(err.message));

    function describe(json, latencyMs) {
      const players = json.players || {};
      return {
        online: true,
        host,
        port,
        latencyMs,
        checkedAt: new Date().toISOString(),
        version: json.version ? json.version.name : null,
        protocol: json.version ? json.version.protocol : null,
        motd: stripFormatting(flattenChat(json.description)).trim(),
        players: {
          online: typeof players.online === 'number' ? players.online : null,
          max: typeof players.max === 'number' ? players.max : null,
          // `sample` is a short, optional list — servers may omit or truncate it.
          sample: Array.isArray(players.sample)
            ? players.sample.map((p) => String(p.name)).filter(Boolean)
            : [],
        },
        // A data: URI the page can show directly.
        favicon: typeof json.favicon === 'string' && json.favicon.startsWith('data:image/')
          ? json.favicon
          : null,
      };
    }
  });
}

module.exports = { pingServer, flattenChat, stripFormatting, writeVarInt, readVarInt };
