"""Writes the tiny MBTiles test fixtures in ui/map/src/jvmTest/resources (Pass 23).

Run from the repo root: python tools/fixtures/make_mbtiles_fixtures.py
Uses Python's own sqlite3, an independent SQLite writer, so the test checks our byte sniffing against a real
database file, not against bytes we made up ourselves.
"""
import os
import sqlite3
import struct
import zlib

OUT = "ui/map/src/jvmTest/resources"


def png_1x1():
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    header = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)  # 1x1 px, 8-bit RGB
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00")) + chunk(b"IEND", b"")


def make(name, fmt, tile):
    path = os.path.join(OUT, name)
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path)
    # MBTiles 1.3: a metadata (name, value) table and a tiles table; metadata first, as GDAL and tippecanoe write it.
    db.execute("CREATE TABLE metadata (name text, value text)")
    db.execute("CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)")
    for k, v in [("name", "fixture"), ("format", fmt), ("minzoom", "0"), ("maxzoom", "0"), ("bounds", "-180,-85,180,85")]:
        db.execute("INSERT INTO metadata VALUES (?, ?)", (k, v))
    db.execute("INSERT INTO tiles VALUES (0, 0, 0, ?)", (tile,))
    db.commit()
    db.close()


os.makedirs(OUT, exist_ok=True)
make("raster.mbtiles", "png", png_1x1())
make("vector.mbtiles", "pbf", zlib.compress(b"\x1a\x00"))
with open(os.path.join(OUT, "not-mbtiles.mbtiles"), "w") as f:
    f.write("just text\n")
