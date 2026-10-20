#!/usr/bin/env python3
"""Regenerates the drone mod's PNG assets. Stdlib only: python3 -I tools/gen_textures.py

The entity texture follows the UV table in docs/superpowers/plans/2026-10-19-drone-mod-plan.md
(Task 4) and must stay in sync with DroneModel.createLayer().
"""
import struct
import zlib
from pathlib import Path

ASSETS = Path(__file__).resolve().parent.parent / "src/main/resources/assets/drone"
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


# (texOffs u, v, box w, h, d, base color) — mirrors the plan's UV table.
PARTS = {
    "body": (0, 0, 8, 4, 8, rgb("#3A3D42")),
    "arm": (0, 16, 20, 2, 2, rgb("#2B2D31")),
    "motor_front": (0, 20, 3, 3, 3, rgb("#D02020")),
    "motor_back": (12, 20, 3, 3, 3, rgb("#20B040")),
    "skid": (24, 20, 1, 2, 12, rgb("#1E1E1E")),
    "prop": (0, 34, 10, 1, 2, rgb("#C8CCD0")),
}


def entity_texture():
    img = Image(64, 64)
    for name, (u, v, w, h, d, color) in PARTS.items():
        for face, x, y, fw, fh in box_faces(u, v, w, h, d):
            fill = rgb("#5A5F66") if (name == "body" and face == "top") else color
            img.rect(x, y, fw, fh, fill, edge=darker(fill))
    # A bright LED pixel in the middle of each motor's side faces.
    for name, glow in (("motor_front", rgb("#FF6A6A")), ("motor_back", rgb("#7CFF9E"))):
        u, v, w, h, d, _ = PARTS[name]
        for face, x, y, fw, fh in box_faces(u, v, w, h, d):
            if face not in ("top", "bottom"):
                img.set(x + fw // 2, y + fh // 2, glow)
    return img


def item_texture():
    """Top-down drone, front pointing up: X arms, body, prop discs, red front / green back lights."""
    img = Image(16, 16)
    arm = rgb("#2B2D31")
    for i in range(2, 14):
        for t in (0, 1):
            img.set(i, i + t - 1, arm)
            img.set(15 - i, i + t - 1, arm)
    img.rect(6, 6, 4, 4, rgb("#3A3D42"), edge=rgb("#1E1E1E"))
    img.rect(7, 7, 2, 2, rgb("#5A5F66"))
    prop = rgb("#C8CCD0", 200)
    corners = ((2, 2, "#D02020"), (13, 2, "#D02020"), (2, 13, "#20B040"), (13, 13, "#20B040"))
    for cx, cy, _ in corners:
        for dy in range(-2, 3):
            for dx in range(-2, 3):
                if dx * dx + dy * dy <= 4 and (dx, dy) != (0, 0):
                    img.set(cx + dx, cy + dy, prop)
    for cx, cy, color in corners:
        img.set(cx, cy, rgb(color))
    return img


def main():
    entity_texture().save(ASSETS / "textures/entity/drone.png")
    item = item_texture()
    item.save(ASSETS / "textures/item/drone.png")
    item.scaled(8).save(ASSETS / "icon.png")
    print(f"Wrote textures under {ASSETS}")


if __name__ == "__main__":
    main()
