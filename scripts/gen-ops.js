'use strict';
/**
 * Ensures server/ops.json always grants operator (level 4, bypass limit) to the
 * configured usernames, with correct offline-mode UUIDs (Java's
 * UUID.nameUUIDFromBytes == RFC4122 v3 md5 of "OfflinePlayer:<name>").
 *
 * Idempotent: merges into whatever ops.json already contains, leaving unknown
 * entries untouched; safe to run on every boot before the JVM starts.
 */
 const fs = require('fs');
const crypto = require('crypto');
const path = require('path');

const OPS_USERS = ['m9cherif3', 'm9cherif', 'm9cherif13'];
const SERVER_DIR = path.join(__dirname, '..', 'server');
const OPS_FILE = path.join(SERVER_DIR, 'ops.json');

/** Offline-player UUID exactly like org.bukkit.*/ // eslint-disable-line
function offlineUuid(name) {
  const h = crypto.createHash('md5').update(`OfflinePlayer:${name}`, 'utf8').digest();
  // Set version nibble to 3 and variant to IETF (RFC 4122).
  h[6] = (h[6] & 0x0f) | 0x30;
  h[8] = (h[8] & 0x3f) | 0x80;
  const hex = h.toString('hex').padStart(32, '0');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-`
    + `${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function ensureOps() {
  fs.mkdirSync(SERVER_DIR, { recursive: true });
  let current = [];
  try {
    current = JSON.parse(fs.readFileSync(OPS_FILE, 'utf8'));
  } catch (_) { /* first boot or corrupt — start over */ }
  if (!Array.isArray(current)) current = [];

  let changed = false;
  for (const name of OPS_USERS) {
    const wantUuid = offlineUuid(name);
    const hit = current.find((e) => typeof e?.name === 'string'
      && e.name.toLowerCase() === name.toLowerCase());
    const desired = { ...hit, uuid: wantUuid, name, level: 4, bypassesPlayerLimit: true };
    if (!hit || `${hit.uuid}` !== wantUuid || hit.level !== 4 || !hit.bypassesPlayerLimit) {
      changed = true;
      if (hit) Object.assign(hit, desired);
      else current.push(desired);
    }
  }

  if (changed || !fs.existsSync(OPS_FILE)) {
    const tmp = `${OPS_FILE}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(current, null, 2));
    fs.renameSync(tmp, OPS_FILE);
    console.log(`[gen-ops] wrote ${current.length} operators (${OPS_USERS.join(', ')})`);
  } else {
    console.log('[gen-ops] operators verified, nothing changed');
  }
  return current.filter((e) => OPS_USERS.includes(e.name)).map((e) => e.name);
}

module.exports = { ensureOps };
if (require.main === module) {
  ensureOps();
}
