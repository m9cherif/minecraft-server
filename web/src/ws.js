'use strict';

// A small RFC 6455 implementation — just the framing, shared by the tunnel
// server and the player-side client.
//
// Why not the `ws` package: Hostinger's Node.js app runner installs
// dependencies from package.json, and every dependency is one more thing that
// can fail on a shared host at deploy time. The tunnel needs binary frames,
// close, ping and pong, and nothing else; that is the file below.

const crypto = require('crypto');

const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

const OPCODE = {
  CONTINUATION: 0x0,
  TEXT: 0x1,
  BINARY: 0x2,
  CLOSE: 0x8,
  PING: 0x9,
  PONG: 0xa,
};

// Frames larger than this are refused rather than buffered. Minecraft packets
// are far below it; anything above is a bug or an attempt to exhaust memory.
const MAX_FRAME_BYTES = 4 * 1024 * 1024;

/** The `Sec-WebSocket-Accept` value for a client's `Sec-WebSocket-Key`. */
function acceptValue(key) {
  return crypto.createHash('sha1').update(key + GUID).digest('base64');
}

/** A fresh `Sec-WebSocket-Key` for the client side of a handshake. */
function generateKey() {
  return crypto.randomBytes(16).toString('base64');
}

/**
 * Build one frame. Clients MUST mask (RFC 6455 §5.3); servers MUST NOT.
 */
function encodeFrame(opcode, payload = Buffer.alloc(0), mask = false) {
  const data = Buffer.isBuffer(payload) ? payload : Buffer.from(payload);
  const length = data.length;

  let header;
  if (length < 126) {
    header = Buffer.alloc(2);
    header[1] = length;
  } else if (length < 65536) {
    header = Buffer.alloc(4);
    header[1] = 126;
    header.writeUInt16BE(length, 2);
  } else {
    header = Buffer.alloc(10);
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(length), 2);
  }
  header[0] = 0x80 | opcode; // FIN set: this implementation never fragments.

  if (!mask) return Buffer.concat([header, data]);

  header[1] |= 0x80;
  const maskingKey = crypto.randomBytes(4);
  const masked = Buffer.allocUnsafe(length);
  for (let i = 0; i < length; i++) masked[i] = data[i] ^ maskingKey[i & 3];
  return Buffer.concat([header, maskingKey, masked]);
}

/** Close frame with a status code and optional reason. */
function encodeClose(code = 1000, reason = '', mask = false) {
  const reasonBytes = Buffer.from(reason, 'utf8');
  const payload = Buffer.allocUnsafe(2 + reasonBytes.length);
  payload.writeUInt16BE(code, 0);
  reasonBytes.copy(payload, 2);
  return encodeFrame(OPCODE.CLOSE, payload, mask);
}

/**
 * Incremental frame parser. Feed it whatever the socket hands you; it calls
 * `onFrame({ opcode, payload, fin })` once per complete frame and `onError`
 * on anything malformed.
 *
 * Fragmented messages are passed through frame by frame. The tunnel relays a
 * byte stream, so continuation frames need no reassembly — their payloads are
 * simply the next bytes. Control frames (close/ping/pong) are never fragmented
 * by the spec, so each arrives whole.
 */
class FrameParser {
  constructor({ onFrame, onError, requireMask = false, maxFrameBytes = MAX_FRAME_BYTES }) {
    this.onFrame = onFrame;
    this.onError = onError;
    this.requireMask = requireMask;
    this.maxFrameBytes = maxFrameBytes;
    this.buffer = Buffer.alloc(0);
    this.failed = false;
  }

  push(chunk) {
    if (this.failed) return;
    this.buffer = this.buffer.length ? Buffer.concat([this.buffer, chunk]) : chunk;

    for (;;) {
      if (this.buffer.length < 2) return;

      const first = this.buffer[0];
      const second = this.buffer[1];
      const fin = (first & 0x80) !== 0;
      const opcode = first & 0x0f;
      const masked = (second & 0x80) !== 0;
      let length = second & 0x7f;
      let offset = 2;

      if ((first & 0x70) !== 0) return this.#fail('Reserved bits set (no extension negotiated)');
      if (masked !== this.requireMask) {
        return this.#fail(this.requireMask ? 'Client frame is not masked' : 'Server frame is masked');
      }

      if (length === 126) {
        if (this.buffer.length < offset + 2) return;
        length = this.buffer.readUInt16BE(offset);
        offset += 2;
      } else if (length === 127) {
        if (this.buffer.length < offset + 8) return;
        const big = this.buffer.readBigUInt64BE(offset);
        if (big > BigInt(this.maxFrameBytes)) return this.#fail('Frame is too large');
        length = Number(big);
        offset += 8;
      }

      if (length > this.maxFrameBytes) return this.#fail('Frame is too large');
      if (opcode >= 0x8) {
        // Control frames: at most 125 bytes, never fragmented.
        if (length > 125) return this.#fail('Control frame payload is too long');
        if (!fin) return this.#fail('Control frame is fragmented');
      }

      const maskingKey = masked ? this.buffer.subarray(offset, offset + 4) : null;
      if (masked) offset += 4;

      if (this.buffer.length < offset + length) return; // Wait for the payload.

      let payload = Buffer.from(this.buffer.subarray(offset, offset + length));
      if (maskingKey) {
        for (let i = 0; i < payload.length; i++) payload[i] ^= maskingKey[i & 3];
      }
      this.buffer = this.buffer.subarray(offset + length);

      this.onFrame({ opcode, payload, fin });
      if (this.failed) return;
    }
  }

  #fail(message) {
    this.failed = true;
    this.buffer = Buffer.alloc(0);
    if (this.onError) this.onError(new Error(message));
  }
}

module.exports = {
  OPCODE,
  MAX_FRAME_BYTES,
  GUID,
  acceptValue,
  generateKey,
  encodeFrame,
  encodeClose,
  FrameParser,
};
