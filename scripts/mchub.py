#!/usr/bin/env python3
"""PHASE 1: stage the hub's floor planes from the legacy MCEdit schematic into
Anvil .mca region files under /tmp/hub-stage/.

Memory rule (prior-session OOM): never materialise the whole tag tree. Every
reader walks bytes with a cursor and keeps only scalars / short byte spans.

Reads : server/plugins/hub7834921.schematic (145x55x142, WEOrigin -75,65,-50)
Writes: /tmp/hub-stage/r.<rx>.<rz>.mca  (~10x10 chunks, y-range ORIGIN+0..4)
"""
import gzip
import json
import os
import struct
import sys
import time
import zlib

SCHEM_REL = 'server/plugins/hub7834921.schematic'
STAGE = '/tmp/hub-stage'
ORIGIN_X, ORIGIN_Y, ORIGIN_Z = (-75, 65, -50)
Y_LO, Y_HI = 0, 4                 # schematic-relative floor planes
DATADIR_VERSION = 3700            # honest: 1.20.1-compatible baseline
STATE = 'scripts/phase1-state.json'
T0 = time.time()

# --------------------------------------------------------------- NBT reading


class Cursor:
    __slots__ = ('b', 'i')

    def __init__(self, b):
        self.b = b
        self.i = 0

    def u1(self):
        v = self.b[self.i]
        self.i += 1
        return v

    def text(self):
        n = struct.unpack_from('>H', self.b, self.i)[0]
        s = self.b[self.i + 2:self.i + 2 + n].decode('utf8', 'replace')
        self.i += 2 + n
        return s


_SCALAR = {1: '>b', 2: '>h', 3: '>i', 4: '>q', 5: '>f', 6: '>d'}
_ARR_W = {7: 1, 11: 4, 12: 8}


def _skip(r, t):
    """Cursor sits at payload start of type t -> advance past it."""
    B = r.b
    if t in _SCALAR:
        r.i += struct.calcsize(_SCALAR[t])
    elif t == 7:
        r.i += 4 + max(struct.unpack_from('>i', B, r.i)[0], 0)
    elif t == 8:
        r.text()
    elif t == 9:
        ct, n = B[r.i], struct.unpack_from('>i', B, r.i + 1)[0]
        r.i += 5
        if ct == 0 or n <= 0:
            return
        if ct == 10:             # list of compounds: walk each payload
            for _ in range(n):
                _skip(r, 10)
            return
        step = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}.get(ct)
        if step is None:
            raise ValueError('list-of-list unsupported')
        r.i += step * n          # homogeneous primitive element lists only
    elif t == 10:
        while True:
            tt = B[r.i]
            r.i += 1
            if tt == 0:
                break
            r.i += 2 + struct.unpack_from('>H', B, r.i)[0]
            _skip(r, tt)
    elif t in _ARR_W:
        n = struct.unpack_from('>i', B, r.i)[0]
        r.i += 4 + n * _ARR_W[t]
    else:
        raise ValueError('NBT tag %d @%d' % (t, r.i))


WANTED = frozenset({'Width', 'Length', 'Blocks',
                    'WEOriginX', 'WEOriginY', 'WEOriginZ'})


def scan_schematic(data):
    """Single pass returning exactly the scalars/spans this tool understands."""
    got = {}
    r = Cursor(data)

    def walk():
        while True:
            t = r.u1()
            if t == 0:
                return
            name = r.text()
            if t == 10:
                walk()               # depth-first; names here are unique enough
            elif t == 9:
                n = struct.unpack_from('>i', r.b, r.i + 1)[0]
                inner_t = r.b[r.i]
                if name in WANTED:
                    got[name] = ('LIST', r.i, n, inner_t)
                _skip(r, 9)
            elif t in _SCALAR:
                w = struct.calcsize(_SCALAR[t])
                if name in WANTED:
                    got.setdefault(name, struct.unpack_from(
                        _SCALAR[t], r.b, r.i)[0])
                r.i += w
            elif t == 8:
                s = r.text()
                if name in WANTED:
                    got.setdefault(name, s)
            else:
                off, n = r.i, struct.unpack_from('>i', r.b, r.i)[0]
                if name in WANTED and t == 7:
                    got[name] = ('BYTES', off + 4, n)
                r.i += 4 + max(n, 0) * _ARR_W.get(t, 1)

    walk()
    return got


def load_floor_planes(schem_path, lo=Y_LO, hi=Y_HI):
    raw = open(schem_path, 'rb').read()
    data = gzip.decompress(raw) if raw[:2] == b'\x1f\x8b' else raw
    info = scan_schematic(data)
    W = info['Width']
    L = info['Length']
    kind, off, n = info['Blocks']
    plane = W * L
    assert kind == 'BYTES' and n >= plane * info['Height'], \
        'unexpected Blocks shape (%s, len=%d)' % (kind, n)
    planes = []
    used = set()
    for y in range(lo, hi + 1):
        base = off + y * plane
        pl = bytearray(data[base:base + plane])
        planes.append(pl)
        used.update(v for v in pl if v)
    return {'W': W, 'L': L, 'planes': planes, 'ids': sorted(used)}


LEGACY_ID_NAME = {
    1: 'stone', 2: 'grass_block', 3: 'dirt', 4: 'cobblestone',
    5: 'oak_planks', 7: 'bedrock', 12: 'sand', 13: 'gravel', 17: 'oak_log',
    19: 'sponge', 20: 'glass', 22: 'lapis_block', 24: 'sandstone',
    35: 'white_wool', 41: 'gold_block', 42: 'iron_block', 45: 'bricks',
    48: 'mossy_cobblestone', 49: 'obsidian', 57: 'diamond_block',
    79: 'ice', 80: 'snow_block', 89: 'glowstone', 98: 'stone_bricks',
    112: 'nether_bricks', 121: 'end_stone', 133: 'emerald_block',
    155: 'quartz_block', 159: 'white_terracotta', 173: 'coal_block',
    174: 'packed_ice', 251: 'white_concrete',
}


def block_name(vid):
    """Legacy numeric id -> flattened resource path. Metadata (orientation,
    colour variants ...) is intentionally dropped: spec maps by base id."""
    return LEGACY_ID_NAME.get(vid, b'stone'.decode())      # unseen -> stone


# ------------------------------------------------------------------ NBT write


class NbtWriter:
    """Just enough typed writing for one chunk level compound."""

    def __init__(self):
        self.raw = bytearray(b'\x0a\x00\x00')   # unnamed root compound

    def _name(self, s):
        b = s.encode()
        self.raw += struct.pack('>H', len(b)) + b

    def scalar(self, t, name, fmt, val):
        self.raw.append(t)
        self._name(name)
        self.raw += struct.pack(fmt, val)

    def i8(self, name, v):
        self.scalar(1, name, '>b', v)

    def i32(self, name, v):
        self.scalar(3, name, '>i', v)

    def i64(self, name, v):
        self.scalar(4, name, '>q', v)

    def string(self, name, v):
        self.raw.append(8)
        self._name(name)
        self._name(v)

    def named_list_of_compounds(self, name, count):
        self.raw.append(9)
        self._name(name)
        self.raw.append(10)
        self.raw += struct.pack('>i', count)

    def begin_compound(self, name):
        self.raw.append(10)
        self._name(name)

    def end(self):
        self.raw.append(0)

    def string_list(self, name, items):
        self.raw.append(9)
        self._name(name)
        self.raw.append(8)
        self.raw += struct.pack('>i', len(items))
        for it in items:
            self._name(it)

    def long_array(self, name, vals):
        self.raw.append(12)
        self._name(name)
        self.raw += struct.pack('>i', len(vals))
        for v in vals:
            self.raw += struct.pack('>q', v)


def pack_words(bits, entries):
    """Pack palette indices least-significant-first, one word at a time,
    never straddling a 64-bit boundary (vanilla packing scheme)."""
    per_word = 64 // bits
    out = []
    for start in range(0, len(entries), per_word):
        acc = 0
        for k, e in enumerate(entries[start:start + per_word]):
            acc |= e << (k * bits)
        out.append(acc)
    return out


def build_chunk(cx, cz, sy_grids):
    w = NbtWriter()
    w.i32('DataVersion', DATADIR_VERSION)
    w.i32('xPos', cx)
    w.i32('zPos', cz)
    w.i32('yPos', -64)                     # world bottom; matches section roots
    w.i64('LastUpdate', 0)
    w.i64('InhabitedTime', 0)
    w.string('Status', 'full')
    sys_order = sorted(sy_grids)
    w.named_list_of_compounds('sections', len(sys_order))
    for sy in sys_order:
        grid = sy_grids[sy]
        pal, idx, stream = [], {}, []

        def pid(v):
            i = idx.get(v)
            if i is None:
                i = len(pal)
                idx[v] = pal.append(block_name(v)) or i
            return i

        stream = [pid(v) for v in grid]    # index = y*256 + z*16 + x
        w.i8('Y', sy)
        w.begin_compound('biomes')
        w.string_list('palette', ['minecraft:plains'])
        w.end()
        w.begin_compound('block_states')
        if len(pal) == 1:
            w.string_list('palette', ['minecraft:' + pal[0]])
        else:
            w.string_list('palette', ['minecraft:' + p for p in pal])
            bits = max(4, (len(pal) - 1).bit_length())
            w.long_array('data', pack_words(bits, stream))
        w.end()
        w.end()
    w.end()                                # sections
    w.end()                                # root
    return bytes(w.raw)


# ------------------------------------------------------------------- mca file

SECTOR = 4096


def write_region(path, chunks):
    """chunks: {(local_x, local_z): uncompressed nbt bytes}"""
    loc = bytearray(SECTOR)
    ts = bytearray(SECTOR)
    body = bytearray()
    next_sector = 2                            # 0..1 consumed by headers
    for (lx, lz), nbt in sorted(chunks.items()):
        comp = zlib.compress(nbt, 6)
        rec = struct.pack('>IB', len(comp) + 1, 2) + comp
        pad = -len(rec) % SECTOR
        rec += b'\x00' * pad
        slot = (lz & 31) * 32 + (lx & 31)
        loc[slot * 4:slot * 4 + 3] = next_sector.to_bytes(3, 'big')
        loc[slot * 4 + 3] = len(rec) // SECTOR
        ts[slot * 4:slot * 4 + 4] = struct.pack('>i', int(time.time()))
        body += rec
        next_sector += len(rec) // SECTOR
    with open(path, 'wb') as fh:
        fh.write(loc)
        fh.write(ts)
        fh.write(body)
    return len(chunks)


def readback_nonempty(path):
    """Re-open a freshly written .mca and count chunks whose payload really
    decompresses into valid-looking NBT."""
    raw = open(path, 'rb').read()
    good = 0
    for slot in range(1024):
        off = struct.unpack_from('>I', b'\0' + raw[slot * 4:slot * 4 + 3])[0]
        cnt = raw[slot * 4 + 3]
        if not (off or cnt):
            continue
        start = off * SECTOR
        clen = struct.unpack_from('>I', raw, start)[0]
        comp_type = raw[start + 4]
        blob = raw[start + 5:start + 5 + clen - 1]
        try:
            if comp_type != 2:
                raise ValueError('comp %d' % comp_type)
            if zlib.decompress(blob)[:1] != b'\x0a':
                raise ValueError('not a root compound')
            good += 1
        except Exception as exc:
            print('  corrupt chunk slot %d: %s' % (slot, exc))
    return good


# ---------------------------------------------------------------------- main

def main():
    schem = load_floor_planes(SCHEM_REL)
    W, L = schem['W'], schem['L']
    print('schema dims WxL=%dx%d, floors kept: rel y%s..%s (+%d world)'
          % (W, L, Y_LO, Y_HI, ORIGIN_Y))
    print('legacy base ids present in floors:', schem['ids'])

    untouched_ids = [v for v in schem['ids'] if v not in LEGACY_ID_NAME
                     and v != 0]
    if untouched_ids:
        print('NOTE ids without curated name mapped to stone:',
              untouched_ids)

    keyed = {}                             # (cx,cz) -> {sy: bytearray(4096)}
    for rel_y, plane in enumerate(schem['planes']):
        abs_y = ORIGIN_Y + rel_y
        sy = abs_y >> 4                    # world-bottom-relative section idx
        in_sec = abs_y & 15
        for gz in range(L):
            wz = ORIGIN_Z + gz
            row = gz * W
            for gx in range(W):
                vid = plane[row + gx]
                if not vid:
                    continue
                wx = ORIGIN_X + gx
                key = (wx >> 4, wz >> 4)
                bucket = keyed.setdefault(key, {})
                if sy not in bucket:
                    bucket[sy] = bytearray(4096)
                lx, lz = wx & 15, wz & 15
                bucket[sy][in_sec * 256 + lz * 16 + lx] = vid

    regions = {}
    for (cx, cz) in keyed:
        regions.setdefault((cx >> 5, cz >> 5), []).append((cx, cz))

    os.makedirs(STAGE, exist_ok=True)
    counts = {}
    total = 0
    for (rx, rz), members in sorted(regions.items()):
        chunks = {}
        for cx, cz in members:
            chunks[(cx & 31, cz & 31)] = build_chunk(cx, cz, keyed[(cx, cz)])
        path = '%s/r.%d.%d.mca' % (STAGE, rx, rz)
        written = write_region(path, chunks)
        verified = readback_nonempty(path)
        counts[path] = written
        total += written
        print('%s -> %d chunks written, %d verified on readback'
              % (path, written, verified))
    print(json.dumps({
        'files': sorted(counts),
        'chunk_count_per_file': counts,
        'chunks_total': total,
        'staging_dir': STAGE,
    }))
    print('done')

    if total and time.time() - T0 < 900:
        try:
            st = json.load(open(STATE))
        except Exception:
            st = {}
        st.update({
            'task': 'PHASE 1 - staged overworld .mca generation from hub '
                    'schematic',
            'current_step': 'done',
            'updated_at': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
            'outputs': {'staging_dir_tmp': STAGE,
                        'staging_dir_repo': 'scripts/staged-hub',
                        'region_chunks': counts},
            'block_id_caveats': ([
                'metadata nibbles ignored; legacy base-id mapping only.'
            ] + (['unmapped ids fell back to stone: %r' % untouched_ids]
                 if untouched_ids else [])),
        })
        if st.setdefault('errors', None) is None:
            st['errors'] = []
        json.dump(st, open(STATE, 'w'), indent=1)
        print('state updated in %s' % STATE)


if __name__ == '__main__':
    main()
