#!/usr/bin/env python3
"""Generate placeholder PS3 game folders for UI screenshots: PARAM.SFO with a
title/serial, a solid-gradient ICON0.PNG (320x176), and a dummy EBOOT.BIN.
Fictional titles only — nothing pretends to be a real game."""

import os
import struct
import sys
import zlib

GAMES = [
    ("Aurora Vale", "DEMO00001", (58, 90, 140)),
    ("Hex Runner", "DEMO00002", (60, 120, 90)),
    ("Starlit Coast", "DEMO00003", (120, 70, 110)),
    ("Circuit Breaker", "DEMO00004", (140, 100, 50)),
]


def sfo(pairs):
    """Minimal PARAM.SFO writer (utf8 entries only)."""
    keys = b""
    data = b""
    index = b""
    entries = []
    for key, value in pairs:
        raw = value.encode() + b"\0"
        entries.append((len(keys), 0x0204, len(raw), len(raw), len(data)))
        keys += key.encode() + b"\0"
        data += raw
    key_table = 20 + 16 * len(entries)
    while key_table % 4:
        key_table += 1
    data_table = key_table + len(keys)
    while data_table % 4:
        data_table += 1
    out = struct.pack("<4sIIII", b"\0PSF", 0x101, key_table, data_table, len(entries))
    for key_off, fmt, ln, mx, off in entries:
        out += struct.pack("<HHIII", key_off, fmt, ln, mx, off)
    out = out.ljust(key_table, b"\0") + keys
    out = out.ljust(data_table, b"\0") + data
    return out


def png(width, height, rgb):
    """Solid-ish PNG with a vertical falloff so tiles do not look flat."""

    def chunk(tag, payload):
        c = tag + payload
        return struct.pack(">I", len(payload)) + c + struct.pack(">I", zlib.crc32(c))

    rows = b""
    for y in range(height):
        shade = 1.0 - 0.55 * y / height
        r, g, b = (int(v * shade) for v in rgb)
        rows += b"\0" + bytes([r, g, b]) * width
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(rows))
        + chunk(b"IEND", b"")
    )


def main(dest):
    for title, serial, rgb in GAMES:
        game = os.path.join(dest, title.replace(" ", ""))
        os.makedirs(os.path.join(game, "PS3_GAME", "USRDIR"), exist_ok=True)
        with open(os.path.join(game, "PS3_GAME", "PARAM.SFO"), "wb") as f:
            f.write(sfo([("TITLE", title), ("TITLE_ID", serial)]))
        with open(os.path.join(game, "PS3_GAME", "ICON0.PNG"), "wb") as f:
            f.write(png(320, 176, rgb))
        with open(os.path.join(game, "PS3_GAME", "USRDIR", "EBOOT.BIN"), "wb") as f:
            f.write(b"\x7fELF-demo-placeholder")
        print(game)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "demo-games")
