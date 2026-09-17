#!/usr/bin/env python3
"""Writes the vanilla structure-template NBT files this mod ships.

Usage: python3 tools/nbt_structures.py            # (re)generate all files
       python3 tools/nbt_structures.py --check    # exit 1 if any file on disk differs

Minecraft 1.21.1 reads templates from data/<ns>/structure/<path>.nbt (gzip, big-endian NBT).
"""
import gzip
import io
import struct
import sys
from pathlib import Path

DATA_VERSION = 3955  # Minecraft 1.21.1
ROOT = Path(__file__).resolve().parent.parent
STRUCTURE_DIR = ROOT / "src/main/resources/data/villagercity/structure"

TAG_END, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 8, 9, 10


def _str(out, s):
    b = s.encode("utf-8")
    out.write(struct.pack(">H", len(b)))
    out.write(b)


def _tag_type(v):
    if isinstance(v, int):
        return TAG_INT
    if isinstance(v, str):
        return TAG_STRING
    if isinstance(v, list):
        return TAG_LIST
    if isinstance(v, dict):
        return TAG_COMPOUND
    raise TypeError(type(v))


def _payload(out, v):
    t = _tag_type(v)
    if t == TAG_INT:
        out.write(struct.pack(">i", v))
    elif t == TAG_STRING:
        _str(out, v)
    elif t == TAG_LIST:
        elem = _tag_type(v[0]) if v else TAG_END
        out.write(struct.pack(">bi", elem, len(v)))
        for e in v:
            _payload(out, e)
    else:
        for k, e in v.items():
            out.write(struct.pack(">b", _tag_type(e)))
            _str(out, k)
            _payload(out, e)
        out.write(struct.pack(">b", TAG_END))


def encode(root):
    raw = io.BytesIO()
    raw.write(struct.pack(">b", TAG_COMPOUND))
    _str(raw, "")
    _payload(raw, root)
    buf = io.BytesIO()
    # mtime=0 keeps the output byte-identical across runs, so --check works.
    with gzip.GzipFile(fileobj=buf, mode="wb", mtime=0) as gz:
        gz.write(raw.getvalue())
    return buf.getvalue()


def template(size, blocks):
    """blocks: dict[(x, y, z)] -> (name, {prop: value})."""
    palette, index, entries = [], {}, []
    for pos in sorted(blocks, key=lambda p: (p[1], p[2], p[0])):
        name, props = blocks[pos]
        key = (name, tuple(sorted(props.items())))
        if key not in index:
            index[key] = len(palette)
            state = {"Name": name}
            if props:
                state["Properties"] = dict(sorted(props.items()))
            palette.append(state)
        entries.append({"pos": list(pos), "state": index[key]})
    return {
        "DataVersion": DATA_VERSION,
        "size": list(size),
        "palette": palette,
        "blocks": entries,
        "entities": [],
    }


def starter_house():
    """5x5x5 oak house: cobblestone floor, log corners, plank walls and roof,
    a door in the z=0 wall, glass windows east/west, a bed and a wall torch inside.
    Interior cells are explicit air so the builder clears obstructions."""
    b = {}
    for x in range(5):
        for z in range(5):
            b[(x, 0, z)] = ("minecraft:cobblestone", {})
            b[(x, 4, z)] = ("minecraft:oak_planks", {})
            for y in (1, 2, 3):
                edge = x in (0, 4) or z in (0, 4)
                corner = x in (0, 4) and z in (0, 4)
                if corner:
                    b[(x, y, z)] = ("minecraft:oak_log", {"axis": "y"})
                elif edge:
                    b[(x, y, z)] = ("minecraft:oak_planks", {})
                else:
                    b[(x, y, z)] = ("minecraft:air", {})
    door = {"facing": "south", "hinge": "left", "open": "false", "powered": "false"}
    b[(2, 1, 0)] = ("minecraft:oak_door", dict(door, half="lower"))
    b[(2, 2, 0)] = ("minecraft:oak_door", dict(door, half="upper"))
    b[(0, 2, 2)] = ("minecraft:glass", {})
    b[(4, 2, 2)] = ("minecraft:glass", {})
    b[(1, 1, 3)] = ("minecraft:red_bed", {"facing": "north", "part": "foot", "occupied": "false"})
    b[(1, 1, 2)] = ("minecraft:red_bed", {"facing": "north", "part": "head", "occupied": "false"})
    # Hangs on the inside of the plank wall at z=4, above the bed; a wall torch faces away from its wall.
    b[(2, 2, 3)] = ("minecraft:wall_torch", {"facing": "north"})
    return template((5, 5, 5), b)


def empty(size):
    return template(size, {})


FILES = {
    "empty.nbt": lambda: empty((9, 5, 9)),
    "test_area.nbt": lambda: empty((48, 12, 48)),
    "blueprint/starter_house.nbt": starter_house,
}


def main():
    check = "--check" in sys.argv
    stale = []
    for rel, build in FILES.items():
        path = STRUCTURE_DIR / rel
        data = encode(build())
        if check:
            if not path.exists() or path.read_bytes() != data:
                stale.append(rel)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            print(f"wrote {path.relative_to(ROOT)} ({len(data)} bytes)")
    if stale:
        print("stale structure files: " + ", ".join(stale))
        sys.exit(1)


if __name__ == "__main__":
    main()
