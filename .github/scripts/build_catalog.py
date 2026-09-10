#!/usr/bin/env python3
"""Regenerates catalog/maps.json and catalog/kits.json.

Maps come from the release's assets, kits from the kits/ folder, so adding either
only means uploading the file — this then keeps the catalog in step.

Run with no arguments from the repo root. Reads GITHUB_REPOSITORY / MAPS_TAG /
GITHUB_TOKEN from the environment when present.
"""

import json
import os
import re
import sys
import urllib.error
import urllib.request

ARCHIVE_SUFFIXES = (".zip", ".rar")

REPO = os.environ.get("GITHUB_REPOSITORY", "LeeBingsu/import-simulators")
TAG = os.environ.get("MAPS_TAG", "maps-v1")
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def display_name(asset_name: str) -> str:
    """Best guess at a map's real name from its asset filename.

    GitHub rewrites spaces in asset names to dots, so dots generally read back as
    spaces — except between digits, where they are almost always a version number
    ("Theo.vs.oroboros.V1.0" -> "Theo vs oroboros V1.0").
    """
    stem = asset_name
    for suffix in ARCHIVE_SUFFIXES:
        if stem.lower().endswith(suffix):
            stem = stem[: -len(suffix)]
            break
    # A dot survives only when it sits between two digits.
    return re.sub(r"(?<!\d)\.|\.(?!\d)", " ", stem)


def fetch_assets() -> list:
    url = f"https://api.github.com/repos/{REPO}/releases/tags/{TAG}"
    req = urllib.request.Request(url, headers={
        "User-Agent": "import-simulators-catalog",
        "Accept": "application/vnd.github+json",
    })
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r).get("assets", [])
    except urllib.error.HTTPError as e:
        if e.code == 404:
            print(f"no release tagged {TAG}; leaving maps.json alone", file=sys.stderr)
            return []
        raise


def load(path: str):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return []


def write(path: str, data) -> bool:
    """Writes only when the content changed, so an unchanged run commits nothing."""
    text = json.dumps(data, indent=1, ensure_ascii=False) + "\n"
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            if f.read() == text:
                return False
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    return True


def build_maps() -> bool:
    assets = fetch_assets()
    if not assets:
        return False

    path = os.path.join(ROOT, "catalog", "maps.json")
    # Names already in the catalog win: some were set by hand and cannot be
    # recovered from the filename, e.g. "ManePear's training simulator", whose
    # apostrophe GitHub replaced with a dot.
    known = {entry["url"]: entry["name"] for entry in load(path)}

    maps = []
    for asset in assets:
        if not asset["name"].lower().endswith(ARCHIVE_SUFFIXES):
            continue
        url = asset["browser_download_url"]
        maps.append({
            "name": known.get(url) or display_name(asset["name"]),
            "url": url,
            "size": asset["size"],
        })
    maps.sort(key=lambda m: m["name"].lower())
    changed = write(path, maps)
    print(f"maps.json: {len(maps)} entries{' (updated)' if changed else ' (unchanged)'}")
    return changed


def build_kits() -> bool:
    kits_dir = os.path.join(ROOT, "kits")
    kits = [
        {"name": name, "size": os.path.getsize(os.path.join(kits_dir, name))}
        for name in sorted(os.listdir(kits_dir))
        if name.endswith(".json")
    ]
    changed = write(os.path.join(ROOT, "catalog", "kits.json"), kits)
    print(f"kits.json: {len(kits)} entries{' (updated)' if changed else ' (unchanged)'}")
    return changed


if __name__ == "__main__":
    maps_changed = build_maps()
    kits_changed = build_kits()
    print(f"changed={'true' if (maps_changed or kits_changed) else 'false'}")
