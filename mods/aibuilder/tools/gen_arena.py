#!/usr/bin/env python3
"""Writes the server-gametest arena: 16 wide, 12 tall, 16 deep, a stone floor at y 0 and air above.

Run from mods/aibuilder:  python3 tools/gen_arena.py
The output is the structure "aibuilder-test:arena" that every placement gametest uses.
"""
from pathlib import Path

SIZE_X, SIZE_Y, SIZE_Z = 16, 12, 16
# The same DataVersion as Fabric's own empty.snbt; the game upgrades older structures when it loads them.
DATA_VERSION = 2730
OUT = (Path(__file__).resolve().parent.parent
       / "src/gametest/resources/data/aibuilder-test/gametest/structure/arena.snbt")


def main():
    entries = []
    for y in range(SIZE_Y):
        for x in range(SIZE_X):
            for z in range(SIZE_Z):
                state = "minecraft:stone" if y == 0 else "minecraft:air"
                entries.append('        {pos: [%d, %d, %d], state: "%s"}' % (x, y, z, state))
    text = ("{\n"
            "    DataVersion: %d,\n" % DATA_VERSION
            + "    size: [%d, %d, %d],\n" % (SIZE_X, SIZE_Y, SIZE_Z)
            + "    data: [\n" + ",\n".join(entries) + "\n    ],\n"
            + "    entities: [],\n"
            + '    palette: [\n        "minecraft:air",\n        "minecraft:stone"\n    ]\n'
            + "}\n")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text)
    print("wrote %s (%d blocks)" % (OUT, len(entries)))


if __name__ == "__main__":
    main()
