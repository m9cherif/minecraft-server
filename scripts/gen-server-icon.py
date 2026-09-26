#!/usr/bin/env python3
"""Generate the Baarcha MC 64x64 server-icon.png with zero image deps.

Concept: dark-navy field, golden pixel-art crescent + star (nod to Tunisia),
and "BM" lettering along the bottom edge. Pure zlib+struct PNG encoder,
works even where PIL is unavailable (this sandbox has no PIL).
"""
import struct
import sys
import zlib

SIZE = 64

# Dark-navy background-friendly palette.
NAVY = (13, 23, 41)
EDGE = (26, 39, 63)
CRESCENT = (250, 204, 21)      # golden
CRESCENT_DIM = (214, 158, 14)  # shaded lower crescent limb
STAR = (255, 240, 170)
LETTER = (232, 236, 244)

OUTER_C = (28, 26)  # crescent outer circle
OUTER_R = 19
INNER_C = (36, 26)  # crescent cut-out circle
INNER_R = 16
STAR_C = (38, 26)   # star centre inside crescent opening
STAR_R = 7


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def crescent_mask(x, y):
    """True when pixel belongs to the crescent."""
    dx, dy = x - OUTER_C[0], y - OUTER_C[1]
    if dx * dx + dy * dy > OUTER_R * OUTER_R:
        return False
    ix, iy = x - INNER_C[0], y - INNER_C[1]
    if ix * ix + iy * iy <= INNER_R * INNER_R:
        return False
    return True


def star_vertices(cx, cy, radius, points=5, rotation=-90.0):
    """Alternate outer/inner vertices of a 5-pointed star."""
    import math
    verts = []
    inner = radius * 0.382
    for i in range(points * 2):
        ang = math.radians(rotation + (180.0 / points) * i)
        r = radius if i % 2 == 0 else inner
        verts.append((cx + r * math.cos(ang), cy + r * math.sin(ang)))
    return verts


def point_in_star(px, py, verts):
    """Ray-casting containment test (treats boundary as inside)."""
    inside = False
    n = len(verts)
    for i in range(n):
        x1, y1 = verts[i]
        x2, y2 = verts[(i + 1) % n]
        if (y1 > py) != (y2 > py):
            xin = x1 + (py - y1) * (x2 - x1) / (y2 - y1)
            if px <= xin:
                inside = not inside
    return inside


BM_ROWS_TALL = [
    "..XXX..",
    ".X...X.",
    "X..X..X",
    "X.....X",
    "X..X..X",
    ".X...X.",
    "..XXX..",
]

# Alternative pixel glyphs, kept adjacent for readability when we rasterize.
GLYPH_B = [
    "XXXX.",
    "X...X",
    "X...X",
    "XXXX.",
    "X...X",
    "X...X",
    "XXXX.",
]
GLYPH_M = [
    "X...X",
    "XX.XX",
    "X.X.X",
    "X...X",
    "X...X",
    "X...X",
    "X...X",
]


def bm_rows():
    """Rasterize B and M into row-major bitmaps."""
    out = []
    for left, right in zip(GLYPH_B, GLYPH_M):
        out.append(left + "." + right)
    return out


def build_pixels():
    verts = star_vertices(STAR_C[0], STAR_C[1], STAR_R)
    pixels = [[None] * SIZE for _ in range(SIZE)]

    for y in range(SIZE):
        for x in range(SIZE):
            # Background: soft edge vignette so it doesn't read as flat black.
            dist_edge = min(x, y, SIZE - 1 - x, SIZE - 1 - y)
            base = EDGE if dist_edge < 2 else NAVY
            pixels[y][x] = base
            if crescent_mask(x, y):
                shade = CRESCENT if y < OUTER_C[1] + 6 else CRESCENT_DIM
                pixels[y][x] = shade
            elif point_in_star(x + 0.5, y + 0.5, verts):
                pixels[y][x] = STAR

    # "BM" letters pinned to the bottom-centre band.
    rows = bm_rows()
    start_y = 54
    width = len(rows[0])
    start_x = (SIZE - width) // 2
    for ry, row in enumerate(rows):
        for rx, ch in enumerate(row):
            if ch == "X":
                px, py = start_x + rx, start_y + ry
                if 0 <= px < SIZE and 0 <= py < SIZE:
                    pixels[py][px] = LETTER
    return pixels


def chunk(tag, data):
    payload = tag + data
    return struct.pack(">I", len(data)) + payload + struct.pack(">I", zlib.crc32(payload) & 0xFFFFFFFF)


def encode_png(pixels):
    # IHDR below declares RGBA (colour type 6), so every pixel must carry
    # four bytes; paint colors are RGB triples and get full opacity here.
    raw = b""
    for row in pixels:
        raw += b"\x00"
        for px in row:
            r, g, b = px
            raw += bytes((r, g, b, 255))
    ihdr = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0)  # 8-bit RGBA
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def main(out_path):
    img = build_pixels()
    blob = encode_png(img)
    with open(out_path, "wb") as fh:
        fh.write(blob)
    print(f"wrote {out_path}: {len(blob)} bytes, {SIZE}x{SIZE}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: gen-server-icon.py OUT_PATH", file=sys.stderr)
        raise SystemExit(2)
    main(sys.argv[1])
