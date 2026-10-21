#!/usr/bin/env python3
"""Regenerates the map and kit lists in catalog/, one pair per mod edition.

Each edition's maps come from the assets of its releases and its kits from its
own folder in the repo (see EDITIONS), so adding either only means uploading the
file — this then keeps the catalog in step. A release can feed more than one
edition: Carpet-both holds maps for both carpet editions.

Run with no arguments from the repo root. Reads GITHUB_REPOSITORY / GITHUB_TOKEN
from the environment when present.
"""

import json
import os
import re
import sys
import urllib.request

ARCHIVE_SUFFIXES = (".zip", ".rar")

REPO = os.environ.get("GITHUB_REPOSITORY", "LeeBingsu/import-simulators")
# (release tags, maps file, kits folder, kits file) for each edition of the mod.
# Tags match case-insensitively. When two releases hold a map of the same name,
# the earlier tag's copy is listed.
EDITIONS = [
    # Import Simulators (vexbot), Minecraft 1.21.11
    (("maps-v1",), "maps.json", "kits", "kits.json"),
    # Import Carpet Maps, Minecraft 1.21.11
    (("carpet-map", "carpet-both"), "carpet-maps.json", "carpet-kits", "carpet-kits.json"),
    # Import Carpet Maps, Minecraft 1.21
    (("carpet-map-1.21", "carpet-both"), "carpet-maps-1.21.json", "carpet-kits-1.21", "carpet-kits-1.21.json"),
]
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


def fetch_releases() -> dict:
    """Every release's assets, keyed by its tag in lower case."""
    releases = {}
    page = 1
    while True:
        url = f"https://api.github.com/repos/{REPO}/releases?per_page=100&page={page}"
        req = urllib.request.Request(url, headers={
            "User-Agent": "import-simulators-catalog",
            "Accept": "application/vnd.github+json",
        })
        token = os.environ.get("GITHUB_TOKEN")
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        with urllib.request.urlopen(req, timeout=60) as r:
            batch = json.load(r)
        for release in batch:
            releases[release["tag_name"].lower()] = release.get("assets", [])
        if len(batch) < 100:
            return releases
        page += 1


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


def build_maps(releases: dict, tags: tuple, filename: str) -> bool:
    found = [tag for tag in tags if tag.lower() in releases]
    if not found:
        print(f"no release tagged {' or '.join(tags)}; leaving {filename} alone", file=sys.stderr)
        return False

    path = os.path.join(ROOT, "catalog", filename)
    # Names already in the catalog win: some were set by hand and cannot be
    # recovered from the filename, e.g. "ManePear's training simulator", whose
    # apostrophe GitHub replaced with a dot.
    known = {entry["url"]: entry["name"] for entry in load(path)}

    maps = []
    names = set()
    for tag in found:
        for asset in releases[tag.lower()]:
            if not asset["name"].lower().endswith(ARCHIVE_SUFFIXES):
                continue
            url = asset["browser_download_url"]
            name = known.get(url) or display_name(asset["name"])
            # The mod tells maps apart by name, so one name is listed once.
            if name.lower() in names:
                continue
            names.add(name.lower())
            maps.append({
                "name": name,
                "url": url,
                "size": asset["size"],
                # Upload time, so the mod can list the newest simulators first.
                "added": asset["created_at"],
            })
    maps.sort(key=lambda m: m["name"].lower())
    changed = write(path, maps)
    print(f"{filename}: {len(maps)} entries{' (updated)' if changed else ' (unchanged)'}")
    return changed


def build_kits(folder: str, filename: str) -> bool:
    kits_dir = os.path.join(ROOT, folder)
    names = sorted(os.listdir(kits_dir)) if os.path.isdir(kits_dir) else []
    kits = [
        {"name": name, "size": os.path.getsize(os.path.join(kits_dir, name))}
        for name in names
        if name.endswith(".json")
    ]
    changed = write(os.path.join(ROOT, "catalog", filename), kits)
    print(f"{filename}: {len(kits)} entries{' (updated)' if changed else ' (unchanged)'}")
    return changed


if __name__ == "__main__":
    releases = fetch_releases()
    changed = False
    for tags, maps_file, kits_folder, kits_file in EDITIONS:
        changed |= build_maps(releases, tags, maps_file)
        changed |= build_kits(kits_folder, kits_file)
    print(f"changed={'true' if changed else 'false'}")
