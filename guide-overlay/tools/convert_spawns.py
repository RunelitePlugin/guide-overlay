#!/usr/bin/env python3
"""Convert a community NPC spawn dataset into this plugin's location format.

The plugin ships a small curated seed and LEARNS exact positions as you play,
which covers normal use. If you want the full game's NPC coordinates
pre-loaded, download a community spawn dump (several projects publish the
game cache's NPC spawn points as JSON/CSV) and convert it with this script,
then in-game: menu -> "Import NPC locations from clipboard".

Accepted inputs:
  JSON list:   [{"name": "Hans", "x": 3221, "y": 3218, "p": 0}, ...]
               (keys may also be "plane"/"z" for the plane)
  CSV lines:   name,x,y,plane

Usage:
  python3 convert_spawns.py spawn_dump.json > npc-locations.json
  python3 convert_spawns.py spawns.csv     > npc-locations.json

When one NPC has multiple spawn points, the first one wins (the plugin
self-corrects from live observation anyway).
"""

import csv
import json
import sys


def from_json(path):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    for row in data:
        name = row.get("name")
        x = row.get("x")
        y = row.get("y")
        p = row.get("p", row.get("plane", row.get("z", 0)))
        if name and isinstance(x, int) and isinstance(y, int):
            yield name, x, y, int(p or 0)


def from_csv(path):
    with open(path, encoding="utf-8", newline="") as f:
        for row in csv.reader(f):
            if len(row) >= 3 and row[0].strip():
                try:
                    yield (row[0].strip(), int(row[1]), int(row[2]),
                           int(row[3]) if len(row) > 3 and row[3].strip() else 0)
                except ValueError:
                    continue  # header or malformed line


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    path = sys.argv[1]
    rows = from_json(path) if path.lower().endswith(".json") else from_csv(path)

    out = {}
    for name, x, y, plane in rows:
        if name not in out and 0 <= x < 20000 and 0 <= y < 20000 and 0 <= plane <= 3:
            out[name] = [x, y, plane]

    json.dump(dict(sorted(out.items())), sys.stdout, indent=1)
    print(file=sys.stderr)
    print(f"{len(out)} locations written", file=sys.stderr)


if __name__ == "__main__":
    main()
