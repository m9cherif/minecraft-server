#!/usr/bin/env python3
"""Stage hub7834921.schematic into an overworld region tree at /tmp/hub-stage-overworld.

Parses the legacy (Alpha 材料format) schematic with nbtlib (no hand-written NBT
parser), converts the ~25 legacy numeric block ids present into modern names,
and emits valid 1.18+-layout region files covering chunks x:-5..4 z:-5..4 with
the schematic centered on chunk (0,0), floor top at y=66.
"""
import io
import os
import struct
import sys
import zlib

import numpy as np
import nbtlib
from nbtlib import tag

SCHEMATIC = "/home/sandbox/workspace/app/server/plugins/hub7834921.schematic"
OUT_ROOT = "/tmp/hub-stage-overworld"
REGION_DIR = os.path.join(OUT_ROOT, "region")
CHUNK_MIN, CHUNK_MAX = -5, 4
DATA_VERSION = 5023  # paper.jar 26.3 world_version


# ---------------------------------------------------------------- step 1 ---
def load_schematic(path):
    f = nbtlib.load(path)
    schem = f.get("Schematic", f)
    w, h, l = int(schem["Width"]), int(schem["Height"]), int(schem["Length"])
    b = np.frombuffer(bytes(schem["Blocks"]), dtype=np.uint8)
    d = np.frombuffer(bytes(schem["Data"]), dtype=np.uint8)
    assert b.size == w * h * l, f"blocks {b.size} != {w}*{h}*{l}"
    return (
        b.reshape(h, l, w),
        d.reshape(h, l, w),
        w,
        h,
        l,
        str(schem["Materials"]),
    )


# ---------------------------------------------------------------- step 2 ---
WOOL = ["white", "orange", "magenta", "light_blue", "yellow", "lime",
        "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown",
        "green", "red", "black"]
FACING = ["east", "south", "west", "north"]

BASE = {
    0: "minecraft:air", 1: "minecraft:stone_bricks", 2: "minecraft:grass_block",
    7: "minecraft:bedrock", 31: "minecraft:short_grass", 42: "minecraft:iron_block",
    57: "minecraft:diamond_block", 89: "minecraft:glowstone",
    98: "minecraft:stone_bricks", 123: "minecraft:redstone_lamp",
    138: "minecraft:beacon", 152: "minecraft:redstone_block",
    159: "minecraft:light_gray_terracotta",
    160: "minecraft:light_gray_stained_glass_pane", 171: "minecraft:white_carpet",
    175: "minecraft:lilac", 70: "minecraft:stone_pressure_plate",
    77: "minecraft:oak_button", 43: "minecraft:smooth_stone",
    44: "minecraft:smooth_stone_slab", 109: "minecraft:stone_brick_stairs",
    139: "minecraft:cobblestone_wall", 35: None, 9: "minecraft:water",
    38: "minecraft:poppy",
}

OVERRIDE = {
    (98, 3): "minecraft:mossy_stone_bricks",
    (159, 5): "minecraft:purple_terracotta",
    (159, 9): "minecraft:cyan_terracotta",
    (171, 7): "minecraft:black_carpet",
    (175, 8): "minecraft:rose_bush",
    (38, 3): "minecraft:poppy",
    (38, 8): "minecraft:cornflower",
}


def state_for(base_id, dv):
    """Return modern block-state string for a legacy (id,data) pair."""
    if base_id == 35:
        return f"minecraft:{WOOL[dv & 15]}_wool"
    hit = OVERRIDE.get((base_id, dv))
    if hit:
        return hit
    name = BASE.get(base_id, "minecraft:stone")
    if base_id == 109:  # stairs: legacy meta 0-3 facing
        return name + "[facing=" + FACING[dv & 3] + "]"
    if base_id == 44:   # slabs: 0..3 bottom-half, 8..11 top-half
        part = "top" if dv & 8 else "bottom"
        return name + f"[type={part}]"
    return name


# ---------------------------------------------------------------- step 3 ---


class MCAFile:
    def __init__(self):
        self.chunks = {}  # (lx,lz) already-zlibbed length-prefixed record?

    def put(self, lz, lx, blob_nbt_bytes):
        self.chunks[(lz, lx)] = blob_nbt_bytes

    def write(self, path):
        locations = bytearray(4096)
        stamps = bytearray(4096)
        body = bytearray()
        sector_no = 2
        for (lz, lx), payload in sorted(self.chunks.items()):
            comp = zlib.compress(payload)
            blen = len(comp) + 5
            sectors = (blen + 4095) // 4096
            rec = struct.pack(">IB", blen - 1, 2) + comp
            rec += b"\x00" * (sectors * 4096 - len(rec))
            li = ((lx % 32)) + ((lz % 32)) * 32
            locations[li * 4:li * 4 + 3] = sector_no.to_bytes(3, "big")
            locations[li * 4 + 3] = sectors
            stamps[li * 4:li * 4 + 4] = struct.pack(">I", 0)
            sector_no += sectors
            body += rec
        with open(path, "wb") as fh:
            fh.write(bytes(locations) + bytes(stamps) + bytes(body))


def pack_section(palette_size, idx_flat):
    """Pack 4096 palette indices into the Minecraft BitStorage layout."""
    bits = max(4, (palette_size - 1).bit_length())
    goods = 64 // bits
    longs_count = (4096 + goods - 1) // goods
    v = np.zeros(longs_count * goods, dtype=np.uint64)
    v[:4096] = idx_flat
    shifted = v.reshape(longs_count, goods) << (np.arange(goods, dtype=np.uint64) *
                                                np.uint64(bits))
    packed = np.bitwise_or.reduce(shifted, axis=1).astype(">u8").tobytes()
    return bits, packed


# serialize compounds via nbtlib itself -------------------------------------


def palette_entry(state_str):
    """'minecraft:x[k=v]'  ->  {'id': 'minecraft:x',
                                'properties': {'k':'v', …}}

    Palette entries carry properties as a COMPOUND. Bracketed postfix
    inside the plain string fails ("Non [a-z0-9/._-] character"), and the
    compound's discriminator key is `id` (verified empirically: entries
    named via `Name:` alone log "No key id in MapLike -> using default").
    Lists are homogeneous in NBT, so even property-less states ride the
    compound form.
    """
    if "[" not in state_str:
        return tag.Compound({"id": tag.String(state_str)})
    name, rest = state_str.split("[", 1)
    props = {}
    for kv in rest.rstrip("]").split(","):
        k, v = kv.split("=", 1)
        props[k.strip()] = tag.String(v.strip())
    c = {"id": tag.String(name)}
    if props:
        c["properties"] = tag.Compound(props)
    return tag.Compound(c)


def build_chunk_nbt(cx, cz, sections_data):
    """sections_data: {sy: (palette_list, packed_bytes)}; produce raw NBT bytes."""
    root = tag.Compound({
        "DataVersion": tag.Int(DATA_VERSION),
        "xPos": tag.Int(cx),
        "yPos": tag.Int(-56),
        "zPos": tag.Int(cz),
        "Status": tag.String("minecraft:full"),
        "lastUpdate": tag.Long(0),
        "InhabitedTime": tag.Long(0),
        "isLightOn": tag.Byte(1),
        "sections": tag.List[tag.Compound]([]),
    })
    ordered_sy = sorted(sections_data.keys())
    for sy in ordered_sy:
        palette_strings, packed = sections_data[sy]
        biomes_pal = tag.List[tag.String](["minecraft:plains"])
        sect = tag.Compound({
            "Y": tag.Byte(sy),
            "biomes": tag.Compound({"palette": biomes_pal}),
            "block_states": tag.Compound({
                "palette": tag.List[tag.Compound](
                    [palette_entry(p) for p in palette_strings])
            }),
        })
        if packed:
            sect["block_states"]["data"] = tag.LongArray(
                np.frombuffer(packed, dtype=">i8"))
        root["sections"].append(sect)

    f = nbtlib.File(root)
    buf = io.BytesIO()
    f.write(buf, byteorder="big")
    return buf.getvalue()


def build_all():
    blocks, data, W, H, L, materials = load_schematic(SCHEMATIC)
    print(f"schematic parsed via nbtlib: Materials={materials} "
          f"W={W} H={H} L={L} blocks-array={blocks.size}")
    solid_rows_below = (blocks != 0).any(axis=(1, 2))
    floor_top_idx = 0
    while (floor_top_idx + 1 < H and solid_rows_below[floor_top_idx + 1]):
        floor_top_idx += 1
    bottom_y = 66 - floor_top_idx
    print(f"floor top layer index {floor_top_idx} -> placed at y=66 "
          f"(schematic occupies y{bottom_y}..{bottom_y + H - 1})")

    ox, oz = -(W // 2), -(L // 2)
    ys, zs, xs = np.nonzero(blocks != 0)  # ~85k items
    print(f"non-air voxel count: {len(ys)}")

    # gather (chunk, section) -> list of (section_slot_index, state)
    acc = {}
    for hy, hz, hx in zip(ys.tolist(), zs.tolist(), xs.tolist()):
        bid = int(blocks[hy, hz, hx])
        state = state_for(bid, int(data[hy, hz, hx]))
        wy = bottom_y + hy
        cx = (hx + ox) >> 4
        cz = (hz + oz) >> 4
        slot = ((wy & 15) << 8) | ((hz + oz & 15) << 4) | (hx + ox & 15)
        acc.setdefault((cx, cz, wy >> 4), []).append((slot, state))

    unknown_ids = (set(int(v) for v in np.unique(blocks)) - set(BASE) -
                   {35})
    if unknown_ids:
        print("WARN unmapped base ids fall back to stone:", sorted(unknown_ids))

    # write out mca files
    os.makedirs(REGION_DIR, exist_ok=True)
    sections_by_chunk = {}

    def pull(cx, cz):
        sections = {}
        for sy in range(bottom_y >> 4, ((bottom_y + H - 1) >> 4) + 1):
            entries = acc.get((cx, cz, sy), [])
            if not entries:
                continue  # untouched section stays implicit all-air
            # palette index 0 must BE air so untouched slots stay air
            states = ["minecraft:air"] + sorted(
                {s for _, s in entries} - {"minecraft:air"})
            idx_of = {s: i for i, s in enumerate(states)}
            flat = np.zeros(4096, dtype=np.uint64)
            for slot, st in entries:
                flat[slot] = idx_of[st]
            bits, packed = pack_section(len(states), flat)
            sections[sy] = (states, packed, bits)
        if not sections:
            sections[(-56 // 16)] = ([TAG_AIR], b"", 4)
        return sections

    TAG_AIR = "minecraft:air"
    mca_out = {}
    chunk_nonempty = 0
    for cx in range(CHUNK_MIN, CHUNK_MAX + 1):
        for cz in range(CHUNK_MIN, CHUNK_MAX + 1):
            secs = pull(cx, cz)  # mode-independent all-air defaults afterward
            if any(x != [TAG_AIR] for x, *_ in secs.values()):
                chunk_nonempty += 1
            top_level_sections = {
                sy: (pal, packed) for sy, (pal, packed, _b) in secs.items()
            }
            rx, rz = cx >> 5, cz >> 5
            mca_out.setdefault((rx, rz), MCAFile()).put(
                cz & 31, cx & 31,
                build_chunk_nbt(cx, cz, top_level_sections))
            sections_by_chunk[(cx, cz)] = secs

    for (rx, rz), mca in sorted(mca_out.items()):
        pth = os.path.join(REGION_DIR, f"r.{rx}.{rz}.mca")
        mca.write(pth)
        print(f"wrote {pth}: {os.path.getsize(pth)} bytes")
    return len(mca_out), chunk_nonempty, blocks, bottom_y, W, H, L


# ---------------------------------------------------------------- step 4 ---
def read_back(bottom_y):
    import glob
    total = 0
    sample_reported = False
    bottom_y_marker = bottom_y  # closure capture
    for path in sorted(glob.glob(os.path.join(REGION_DIR, "*.mca"))):
        with open(path, "rb") as fh:
            raw = fh.read()
            cnt = 0
            for li in range(1024):
                off = int.from_bytes(raw[li * 4:li * 4 + 3], "big")
                if not off:
                    continue
                blen = int.from_bytes(raw[off * 4096:off * 4096 + 4], "big")
                compress_byte = raw[off * 4096 + 4]
                payload = zlib.decompress(
                    raw[off * 4096 + 5:off * 4096 + 5 + (blen - 1)])
                assert compress_byte == 2, f"unexpected compression {compress_byte}"
                gz = io.BytesIO()
                import gzip as gzip_mod
                with gzip_mod.GzipFile(fileobj=gz, mode="wb") as g:
                    g.write(payload)
                chunk_nbt = nbtlib.load(
                    io.BytesIO(gz.getvalue()), gzipped=True)
                root = chunk_nbt
                cx = int(root["xPos"])
                cz = int(root["zPos"])
                cnt += 1
                if not sample_reported and (cx, cz) == (0, 0):
                    # look at world column x=0,z=0 top-down between y117..63
                    col_secs = {int(s["Y"]): s for s in root["sections"]}
                    for wy in range(bottom_y_marker, 118):

                        def _decode(wyy, secs):
                            s = secs.get(wyy >> 4)
                            if s is None or "block_states" not in s:
                                return "minecraft:air"
                            pal = [str(e["id"]) for e in
                                   s["block_states"]["palette"]]
                            idx_flat = ((wyy & 15) << 8) | (8 << 4) | 8
                            if "data" not in s["block_states"]:
                                return pal[0]
                            arr = np.frombuffer(
                                bytes(s["block_states"]["data"]),
                                dtype=">u8")
                            b_ = max(4, (len(pal) - 1).bit_length())
                            g_ = 64 // b_
                            l_, gpos = divmod(idx_flat, g_)
                            v = int(arr[l_]) >> (gpos * b_) & (2**b_ - 1)
                            return pal[v]

                        st = _decode(wy, col_secs)
                        if st != "minecraft:air":
                            print(f"sample chunk (0,0) column x=0 z=8: "
                                  f"first solid below y118 is "
                                  f"{st} at y={wy}")
                            break
                    sample_reported = True
            print(f"{path}: {cnt} chunks")
            total += cnt
    print(f"TOTAL chunks in stage regions: {total}")


if __name__ == "__main__":
    _n_regions, _n_chunks, _blocks, bottom_y, W, H, L = build_all()
    print("\n--- verification ---")
    read_back(bottom_y)
    print("\nDONE ->", OUT_ROOT)
