'use strict';

// A stand-in for a real Minecraft server: it answers the Server List Ping
// handshake and nothing else. Enough to test ping.js without 12 GB of Java.

const net = require('net');
const { writeVarInt, readVarInt } = require('../src/ping');

function writeString(text) {
  const bytes = Buffer.from(text, 'utf8');
  return Buffer.concat([writeVarInt(bytes.length), bytes]);
}

function packet(id, ...parts) {
  const body = Buffer.concat([writeVarInt(id), ...parts]);
  return Buffer.concat([writeVarInt(body.length), body]);
}

/** @returns {Promise<{port: number, close: () => Promise<void>}>} */
function startFakeMinecraft(statusPayload) {
  const server = net.createServer((socket) => {
    let buffer = Buffer.alloc(0);
    let sawHandshake = false;

    socket.on('data', (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      for (;;) {
        const header = readVarInt(buffer, 0);
        if (!header || buffer.length < header.size + header.value) return;
        const body = buffer.subarray(header.size, header.size + header.value);
        buffer = buffer.subarray(header.size + header.value);
        const id = readVarInt(body, 0);

        if (!sawHandshake) {
          sawHandshake = true; // First packet is the handshake; contents unused here.
        } else if (id.value === 0x00) {
          socket.write(packet(0x00, writeString(JSON.stringify(statusPayload))));
        } else if (id.value === 0x01) {
          socket.write(packet(0x01, body.subarray(id.size))); // Echo the long back.
        }
      }
    });
    socket.on('error', () => socket.destroy());
  });

  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () =>
      resolve({
        port: server.address().port,
        close: () => new Promise((done) => server.close(done)),
      })
    );
  });
}

module.exports = { startFakeMinecraft };
