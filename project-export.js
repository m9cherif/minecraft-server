const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const APP_DIR = __dirname;

/** Name the zip and its root folder; also the stem of the download filename. */
const PROJECT_NAME = 'minecraft-tunnel-server';

/** Directories that never belong in a source export: build output, downloaded
    runtimes, backups and VCS metadata. */
const EXCLUDED_DIRS = new Set([
  '.git', 'node_modules', 'jre', 'jdk', 'backups', 'logs', 'crash-reports',
  'cache', 'libraries', 'versions',
]);

/** Basename patterns excluded anywhere in the tree: binaries that are fetched
    or generated at runtime, pid/log scratch files, and any copy of a secret. */
const EXCLUDED_NAMES = new Set([
  'paper.jar', 'playit', 'playit.secret.toml', 'playit-secret.backup.toml',
]);
const EXCLUDED_SUFFIXES = ['.pid', '.log', '.part', '.tmp'];
const EXCLUDED_BASENAMES = new Set(['.env', '.env.local']);

function isExcluded(name) {
  if (EXCLUDED_BASENAMES.has(name) || EXCLUDED_NAMES.has(name)) return true;
  return EXCLUDED_SUFFIXES.some((suffix) => name.endsWith(suffix));
}

/** Every exportable file, as paths relative to APP_DIR, sorted for stable output. */
function collectFiles(dir = APP_DIR, found = []) {
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch (_) {
    return found;
  }
  for (const entry of entries) {
    const abs = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (EXCLUDED_DIRS.has(entry.name)) continue;
      collectFiles(abs, found);
    } else if (entry.isFile() && !isExcluded(entry.name)) {
      found.push(path.relative(APP_DIR, abs));
    }
  }
  return found.sort();
}

// ---------- minimal ZIP writer (deflate via node's zlib, no new deps) ----------

/** MS-DOS date/time fields used by the ZIP local header. */
function dosDateTime(date) {
  const time =
    (date.getHours() << 11) | (date.getMinutes() << 5) | (date.getSeconds() >> 1);
  const day =
    ((date.getFullYear() - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate();
  return { time, day };
}

function crc32(buf) {
  let crc = ~0;
  for (let i = 0; i < buf.length; i++) {
    crc ^= buf[i];
    for (let bit = 0; bit < 8; bit++) {
      crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
    }
  }
  return ~crc >>> 0;
}

/**
 * Build a ZIP of the project source.
 * @returns {Buffer} the complete archive
 */
function buildZip() {
  const files = collectFiles();
  const { time, day } = dosDateTime(new Date());
  const locals = [];
  const centrals = [];
  let offset = 0;

  for (const rel of files) {
    const abs = path.join(APP_DIR, rel);
    const name = `${PROJECT_NAME}/${rel.split(path.sep).join('/')}`;
    const nameBuf = Buffer.from(name, 'utf8');
    let raw;
    try {
      raw = fs.readFileSync(abs);
    } catch (_) {
      continue; // vanished mid-walk (rotated log, etc.)
    }
    const deflated = zlib.deflateRawSync(raw, { level: 6 });
    // Store uncompressed when deflate does not actually pay off.
    const useDeflate = deflated.length < raw.length;
    const data = useDeflate ? deflated : raw;
    const method = useDeflate ? 8 : 0;
    const crc = crc32(raw);

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4); // version needed
    local.writeUInt16LE(0x0800, 6); // UTF-8 names
    local.writeUInt16LE(method, 8);
    local.writeUInt16LE(time, 10);
    local.writeUInt16LE(day, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(raw.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(0, 28);
    locals.push(local, nameBuf, data);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4); // version made by
    central.writeUInt16LE(20, 6); // version needed
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt16LE(method, 10);
    central.writeUInt16LE(time, 12);
    central.writeUInt16LE(day, 14);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(raw.length, 24);
    central.writeUInt16LE(nameBuf.length, 28);
    central.writeUInt16LE(0, 30); // extra
    central.writeUInt16LE(0, 32); // comment
    central.writeUInt16LE(0, 34); // disk
    central.writeUInt16LE(0, 36); // internal attrs
    central.writeUInt32LE(((0o100644 << 16) >>> 0), 38); // external attrs
    central.writeUInt32LE(offset, 42);
    centrals.push(central, nameBuf);

    offset += local.length + nameBuf.length + data.length;
  }

  const centralBuf = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(files.length, 8);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(centralBuf.length, 12);
  end.writeUInt32LE(offset, 16);
  end.writeUInt16LE(0, 20);

  return Buffer.concat([...locals, centralBuf, end]);
}

/** Timestamped download name, e.g. minecraft-tunnel-server-2026-09-26T1403.zip. */
function exportFilename() {
  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  const stamp =
    `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}` +
    `T${pad(now.getHours())}${pad(now.getMinutes())}`;
  return `${PROJECT_NAME}-${stamp}.zip`;
}

module.exports = { buildZip, exportFilename, collectFiles, PROJECT_NAME };
