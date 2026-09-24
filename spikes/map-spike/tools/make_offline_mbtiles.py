"""Builds the tiny synthetic raster .mbtiles used by check M6 (offline source).

Why synthetic: M6 asks "can MapLibre Native read a local mbtiles:// source on both platforms with
networking off?". Real imagery isn't needed to answer that, and downloading real tiles in bulk would
break the OSM / Esri tile policies. Each tile is a flat colour (hue per zoom, checkerboard per x+y)
with a dark 2 px border, so you can see the tile grid and tell zoom levels apart.

Run from the repo root:  python spikes/map-spike/tools/make_offline_mbtiles.py
"""
import colorsys
import pathlib
import sqlite3
import struct
import zlib

MAX_ZOOM = 4  # z0..z4 = 341 tiles, ~20 KB. Enough to pan and zoom a little offline.
SIZE = 256
OUT = pathlib.Path(__file__).parents[1] / "src/commonMain/composeResources/files/offline.mbtiles"


def png(rgb_rows):
    """Minimal RGB PNG writer (stdlib only): IHDR + one zlib IDAT + IEND."""
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    raw = b"".join(b"\x00" + row for row in rgb_rows)  # filter byte 0 (None) per scanline
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def tile(z, x, y):
    r, g, b = colorsys.hsv_to_rgb(z / (MAX_ZOOM + 1), 0.45 if (x + y) % 2 else 0.25, 0.95)
    fill = bytes((int(r * 255), int(g * 255), int(b * 255)))
    edge = b"\x30\x30\x30"
    border_row = edge * SIZE
    inner_row = edge * 2 + fill * (SIZE - 4) + edge * 2
    return png([border_row if i < 2 or i >= SIZE - 2 else inner_row for i in range(SIZE)])


OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.unlink(missing_ok=True)
db = sqlite3.connect(OUT)
db.executescript("""
    CREATE TABLE metadata (name TEXT, value TEXT);
    CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);
    CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row);
""")
db.executemany("INSERT INTO metadata VALUES (?, ?)", [
    ("name", "kft-map-spike-offline"), ("format", "png"), ("type", "baselayer"),
    ("minzoom", "0"), ("maxzoom", str(MAX_ZOOM)), ("bounds", "-180,-85.0511,180,85.0511"),
])
for z in range(MAX_ZOOM + 1):
    n = 2 ** z
    for x in range(n):
        for y in range(n):
            # MBTiles stores rows in TMS order (y flipped); MapLibre Native flips them back when reading.
            db.execute("INSERT INTO tiles VALUES (?, ?, ?, ?)", (z, x, n - 1 - y, tile(z, x, y)))
db.commit()
db.execute("VACUUM")
db.close()
print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")
