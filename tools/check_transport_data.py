#!/usr/bin/env python3
"""Validate Guide Overlay named-place and transport seed files."""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLACE_FILE = ROOT / "src/main/resources/com/hcimguide/place-locations-seed.json"
TRANSPORT_FILE = ROOT / "src/main/resources/com/hcimguide/transport-destinations.json"
FAIRY_CODE = re.compile(r"^[A-D][I-L][P-S]$")


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def load(path: Path):
    with path.open(encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, list):
        fail(f"{path.name} must contain a JSON array")
    return value


def check_point(entry: dict, source: str, index: int) -> None:
    for field in ("x", "y", "plane"):
        if not isinstance(entry.get(field), int):
            fail(f"{source}[{index}].{field} must be an integer")
    if entry["x"] <= 0 or entry["y"] <= 0:
        fail(f"{source}[{index}] has a non-positive coordinate")
    if not 0 <= entry["plane"] <= 3:
        fail(f"{source}[{index}].plane must be between 0 and 3")


def check_places(entries: list[dict]) -> None:
    names: set[str] = set()
    aliases: dict[str, str] = {}
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            fail(f"places[{index}] must be an object")
        name = entry.get("name")
        if not isinstance(name, str) or not name.strip():
            fail(f"places[{index}].name is required")
        key = name.casefold()
        if key in names:
            fail(f"duplicate place name: {name}")
        names.add(key)
        check_point(entry, "places", index)
        values = entry.get("aliases")
        if not isinstance(values, list) or not values:
            fail(f"{name} must have at least one alias")
        for alias in values:
            if not isinstance(alias, str) or len(alias.strip()) < 3:
                fail(f"invalid alias on {name}: {alias!r}")
            normalized = " ".join(alias.casefold().split())
            previous = aliases.get(normalized)
            if previous is not None and previous != name:
                fail(f"alias {alias!r} is shared by {previous!r} and {name!r}")
            aliases[normalized] = name


def check_transports(entries: list[dict]) -> int:
    names: set[str] = set()
    phrases: dict[str, str] = {}
    codes: set[str] = set()
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            fail(f"transports[{index}] must be an object")
        name = entry.get("name")
        if not isinstance(name, str) or not name.strip():
            fail(f"transports[{index}].name is required")
        key = name.casefold()
        if key in names:
            fail(f"duplicate transport name: {name}")
        names.add(key)
        check_point(entry, "transports", index)
        code = entry.get("code")
        values = entry.get("phrases", [])
        if code is None and not values:
            fail(f"{name} needs a fairy-ring code or at least one phrase")
        if code is not None:
            if not isinstance(code, str) or not FAIRY_CODE.fullmatch(code.upper()):
                fail(f"invalid fairy-ring code on {name}: {code!r}")
            code = code.upper()
            if code in codes:
                fail(f"duplicate fairy-ring code: {code}")
            codes.add(code)
        if not isinstance(values, list):
            fail(f"phrases on {name} must be an array")
        for phrase in values:
            if not isinstance(phrase, str) or len(phrase.strip()) < 5:
                fail(f"invalid phrase on {name}: {phrase!r}")
            normalized = " ".join(phrase.casefold().split())
            previous = phrases.get(normalized)
            if previous is not None and previous != name:
                fail(f"transport phrase {phrase!r} is shared by {previous!r} and {name!r}")
            phrases[normalized] = name
    return len(codes)


def main() -> None:
    places = load(PLACE_FILE)
    transports = load(TRANSPORT_FILE)
    check_places(places)
    fairy_codes = check_transports(transports)
    print(f"Validated {len(places)} named places")
    print(f"Validated {len(transports)} transport destinations ({fairy_codes} fairy-ring codes)")


if __name__ == "__main__":
    main()
