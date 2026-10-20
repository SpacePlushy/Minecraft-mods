#!/usr/bin/env python3
"""Regenerates the rocket mod's PNG assets. Stdlib only: python3 -I tools/gen_textures.py

The entity texture's UV table is PARTS below and must stay in sync with RocketModel.createLayer():
each part's (u, v) is its texOffs, and (w, h, d) its box size in pixels. Front = the "north" face.
"""
import struct
import zlib
from pathlib import Path

ASSETS = Path(__file__).resolve().parent.parent / "src/main/resources/assets/rocket"
CLEAR = (0, 0, 0, 0)


def rgb(hex_color, alpha=255):
    h = hex_color.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), alpha)


def darker(color, factor=0.7):
    return tuple(int(c * factor) for c in color[:3]) + (color[3],)


class Image:
    def __init__(self, width, height):
        self.width, self.height = width, height
        self.pixels = [[CLEAR] * width for _ in range(height)]

    def set(self, x, y, color):
        if 0 <= x < self.width and 0 <= y < self.height:
            self.pixels[y][x] = color

    def rect(self, x, y, w, h, color, edge=None):
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                on_edge = xx in (x, x + w - 1) or yy in (y, y + h - 1)
                self.set(xx, yy, edge if (edge and on_edge and w > 2 and h > 2) else color)

    def scaled(self, factor):
        out = Image(self.width * factor, self.height * factor)
        for y in range(out.height):
            for x in range(out.width):
                out.pixels[y][x] = self.pixels[y // factor][x // factor]
        return out

    def save(self, path):
        raw = b"".join(b"\x00" + b"".join(bytes(p) for p in row) for row in self.pixels)

        def chunk(kind, data):
            return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

        header = struct.pack(">IIBBBBB", self.width, self.height, 8, 6, 0, 0, 0)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
                         + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def box_faces(u, v, w, h, d):
    """Minecraft cube UV layout: (name, x, y, width, height) for each face of a box at texOffs(u, v)."""
    return [
        ("top", u + d, v, w, d),
        ("bottom", u + d + w, v, w, d),
        ("east", u, v + d, d, h),
        ("north", u + d, v + d, w, h),
        ("west", u + d + w, v + d, d, h),
        ("south", u + 2 * d + w, v + d, w, h),
    ]



WHITE = rgb("#E8E8E4")
RED = rgb("#C62828")
GRAY = rgb("#4A4C50")
BLUE = rgb("#3D7DD8")
GLINT = rgb("#A8D0FF")

# name: (texOffs u, v, box w, h, d, base color). 128x128 texture, no overlaps.
PARTS = {
    "cabin": (0, 0, 22, 24, 22, WHITE),
    "fin": (88, 0, 8, 14, 2, RED),
    "body": (0, 46, 22, 26, 22, WHITE),
    "nose1": (0, 94, 16, 8, 16, RED),
    "nose2": (64, 94, 10, 6, 10, RED),
    "nose3": (104, 94, 4, 4, 4, RED),
    "nozzle": (64, 110, 12, 6, 12, GRAY),
}
SIDES = ("east", "north", "west", "south")


def entity_texture():
    img = Image(128, 128)
    for name, (u, v, w, h, d, color) in PARTS.items():
        for face, x, y, fw, fh in box_faces(u, v, w, h, d):
            img.rect(x, y, fw, fh, color, edge=darker(color, 0.8))
    faces = lambda name: {f: (x, y, fw, fh) for f, x, y, fw, fh in box_faces(*PARTS[name][:5])}
    # Red band around the top of the lower body, under the cabin.
    for face in SIDES:
        x, y, fw, fh = faces("body")[face]
        img.rect(x, y + 1, fw, 4, RED)
    # Big window on the cabin's front, round portholes on its sides.
    x, y, fw, fh = faces("cabin")["north"]
    img.rect(x + 5, y + 6, 12, 10, BLUE, edge=darker(BLUE))
    img.rect(x + 7, y + 8, 3, 2, GLINT)
    for face in ("east", "west", "south"):
        x, y, fw, fh = faces("cabin")[face]
        img.rect(x + fw // 2 - 2, y + 9, 4, 4, BLUE, edge=darker(BLUE))
    # Dark throat on the nozzle's underside.
    x, y, fw, fh = faces("nozzle")["bottom"]
    img.rect(x + 2, y + 2, fw - 4, fh - 4, rgb("#1A1A1C"))
    return img


def item_texture():
    """Upright rocket: red nose, white body with a blue window, red fins, flame below."""
    img = Image(16, 16)
    for y, (x0, x1) in enumerate([(7, 8), (6, 9), (6, 9)]):  # nose rows 0-2
        for x in range(x0, x1 + 1):
            img.set(x, y, RED)
    for y in range(3, 11):  # body
        for x in range(5, 11):
            img.set(x, y, WHITE if x not in (5, 10) else darker(WHITE, 0.8))
    img.rect(7, 4, 2, 2, BLUE)
    img.set(7, 4, GLINT)
    for y in range(7, 12):  # fins
        img.set(4, y, RED)
        img.set(11, y, RED)
    img.set(3, 10, RED)
    img.set(3, 11, RED)
    img.set(12, 10, RED)
    img.set(12, 11, RED)
    img.rect(6, 11, 4, 1, GRAY)  # nozzle
    for y, (x0, x1, color) in enumerate([(6, 9, "#FFD54F"), (6, 9, "#FFA726"), (7, 8, "#FF7043"), (7, 8, "#E53935")]):
        for x in range(x0, x1 + 1):
            img.set(x, 12 + y, rgb(color))
    return img


def main():
    entity_texture().save(ASSETS / "textures/entity/rocket.png")
    item = item_texture()
    item.save(ASSETS / "textures/item/rocket.png")
    item.scaled(8).save(ASSETS / "icon.png")
    print(f"Wrote textures under {ASSETS}")


if __name__ == "__main__":
    main()
