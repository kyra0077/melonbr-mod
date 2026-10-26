#!/usr/bin/env python3
"""Prepares the embedded games for the "Pokémon BR" APK.

    python tools/pack_android_games.py --list jogos.txt --out <folder>
    gradlew assembleGitHubProdRelease -PembeddedGamesDir=<folder>

The list file is UTF-8, one game per line: "Label|path/to/game.nds" (blank lines and
lines starting with # are ignored; relative paths start at the list's folder).

<folder>/assets/games/  ROMs (hard links when possible), icon_N.png, games.json
<folder>/res/           launcher icon made from the games' own banner icons

Everything here comes from the ROMs, so the folder never goes into the repository.
"""
import argparse
import json
import os
import struct
import sys
import zlib


def banner_icon(data):
    """32x32 banner icon of a DS ROM as rows of RGBA tuples."""
    b = struct.unpack_from("<I", data, 0x68)[0]
    if not b:
        return None
    tiles = data[b + 0x20:b + 0x220]
    pal = struct.unpack_from("<16H", data, b + 0x220)
    colors = []
    for i, c in enumerate(pal):
        r, g, bl = c & 31, (c >> 5) & 31, (c >> 10) & 31
        colors.append(((r << 3) | (r >> 2), (g << 3) | (g >> 2), (bl << 3) | (bl >> 2), 0 if i == 0 else 255))
    px = [[None] * 32 for _ in range(32)]
    for t in range(16):
        tx, ty = t % 4, t // 4
        for y in range(8):
            for x in range(8):
                byte = tiles[t * 32 + y * 4 + x // 2]
                px[ty * 8 + y][tx * 8 + x] = colors[(byte >> 4) if x & 1 else (byte & 15)]
    return px


def write_png(path, px, scale=1, canvas=None):
    """Nearest-neighbour upscale; optionally centred on a transparent square canvas."""
    n = len(px) * scale
    size = canvas or n
    off = (size - n) // 2
    clear = (0, 0, 0, 0)
    rows = []
    for y in range(size):
        row = bytearray([0])
        for x in range(size):
            sx, sy = x - off, y - off
            row += bytes(px[sy // scale][sx // scale] if 0 <= sx < n and 0 <= sy < n else clear)
        rows.append(bytes(row))

    def chunk(tag, payload):
        return struct.pack(">I", len(payload)) + tag + payload + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def grid(icons):
    if len(icons) == 1:
        return icons[0]
    clear = (0, 0, 0, 0)
    px = [[clear] * 64 for _ in range(64)]
    for i, icon in enumerate(icons[:4]):
        ox, oy = (i % 2) * 32, (i // 2) * 32
        for y in range(32):
            for x in range(32):
                px[oy + y][ox + x] = icon[y][x]
    return px


def trimmed_size(path):
    """Size without the 0xFF padding at the end of a cartridge dump (melonDS pads it back)."""
    with open(path, "rb") as f:
        head = f.read(0x1000)
        total = os.fstat(f.fileno()).st_size
        fat_off, fat_size = struct.unpack_from("<II", head, 0x48)
        banner_off = struct.unpack_from("<I", head, 0x68)[0]
        end = max(struct.unpack_from("<I", head, 0x80)[0], banner_off + 0xA00)
        f.seek(fat_off)
        fat = f.read(fat_size)
    for i in range(fat_size // 8):
        start, stop = struct.unpack_from("<II", fat, i * 8)
        if stop > start:
            end = max(end, stop)
    if head[0x12] & 2:  # DSi-enhanced: keep the TWL area
        end = max(end, struct.unpack_from("<I", head, 0x210)[0])
    return min((end + 3) & ~3, total)


def place(src, dst):
    """Trimmed copy of the ROM; a hard link when there's nothing to trim."""
    size = trimmed_size(src)
    if os.path.exists(dst):
        if os.path.getsize(dst) == size:
            return size
        os.remove(dst)
    if size == os.path.getsize(src):
        try:
            os.link(src, dst)
            return size
        except OSError:
            pass
    with open(src, "rb") as fi, open(dst, "wb") as fo:
        left = size
        while left:
            chunk = fi.read(min(left, 1 << 22))
            if not chunk:
                break
            fo.write(chunk)
            left -= len(chunk)
    return size


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--list", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    base = os.path.dirname(os.path.abspath(args.list))
    entries = []
    with open(args.list, encoding="utf-8-sig") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            label, _, path = line.partition("|")
            if not path:
                sys.exit(f"error: expected 'Label|path' in {args.list}: {line}")
            path = path.strip()
            entries.append((label.strip(), path if os.path.isabs(path) else os.path.join(base, path)))
    if not entries:
        sys.exit("error: no games listed")

    games_dir = os.path.join(args.out, "assets", "games")
    os.makedirs(games_dir, exist_ok=True)
    meta, icons = [], []
    for i, (label, path) in enumerate(entries):
        with open(path, "rb") as f:
            head = f.read(0x8000)
            f.seek(struct.unpack_from("<I", head, 0x68)[0])
            banner = f.read(0x840)
        # banner_icon() expects the banner at the offset stored in the header; rebuild a tiny image.
        fake = bytearray(head[:0x200]) + bytearray(0x200)
        struct.pack_into("<I", fake, 0x68, 0x400)
        fake[0x400:0x400 + len(banner)] = banner
        icon = banner_icon(bytes(fake))
        icons.append(icon)
        name = os.path.basename(path)
        size = place(path, os.path.join(games_dir, name))
        write_png(os.path.join(games_dir, f"icon_{i}.png"), icon, scale=8)
        code = head[12:16].decode("ascii", "replace")
        meta.append({"label": label, "file": name, "icon": f"icon_{i}.png", "code": code})
        print(f"[android] {i}: {code} {label} <- {name} ({size} of {os.path.getsize(path)} bytes)")

    with open(os.path.join(games_dir, "games.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1)

    # Launcher icon: adaptive icon (API 26+) plus a plain PNG for older Android.
    res = os.path.join(args.out, "res")
    composite = grid(icons)
    write_png(os.path.join(res, "drawable-nodpi", "pokebr_launcher_fg.png"), composite, scale=4, canvas=432)
    write_png(os.path.join(res, "mipmap-xxxhdpi", "ic_launcher.png"), composite, scale=3)
    os.makedirs(os.path.join(res, "mipmap-anydpi-v26"), exist_ok=True)
    with open(os.path.join(res, "mipmap-anydpi-v26", "ic_launcher.xml"), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@color/pokebr_launcher_bg"/>\n'
                '    <foreground android:drawable="@drawable/pokebr_launcher_fg"/>\n'
                '</adaptive-icon>\n')
    os.makedirs(os.path.join(res, "values"), exist_ok=True)
    with open(os.path.join(res, "values", "pokebr_colors.xml"), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                '    <color name="pokebr_launcher_bg">#FF141A2C</color>\n</resources>\n')
    print(f"[android] {len(meta)} games -> {args.out}")


if __name__ == "__main__":
    main()
