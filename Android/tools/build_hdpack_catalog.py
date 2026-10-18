#!/usr/bin/env python3
"""Builds the downloadable HD pack catalog (hdpacks.json) from a folder of pack zips.

Each zip becomes a GitHub release asset; the catalog lists them with size, SHA-256,
and the ROM SHA-1s from <supportedRom> so the app can match packs to the user's ROMs.

Hand edits to "title", "aliases" and "notes" in an existing catalog are preserved
when re-running, so titles only need to be cleaned up once.

Usage:
  build_hdpack_catalog.py PACKS_DIR OUT_DIR [--repo izzy2lost/NESHD-HDPacks] [--tag packs-v1]

OUT_DIR receives hdpacks.json plus an "assets/" folder of hard links (or copies)
renamed to GitHub-safe asset names, ready for:
  gh release upload TAG OUT_DIR/assets/* --repo REPO --clobber
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import zipfile

SUPPORTED_ROM = re.compile(r"<supportedRom>\s*([0-9A-Fa-f]{40})")


def asset_name(file_name: str) -> str:
    stem = re.sub(r"(?i)\.zip$", "", file_name)
    stem = re.sub(r"[^A-Za-z0-9]+", "-", stem).strip("-")
    return f"{stem}.zip"


def default_title(file_name: str) -> str:
    stem = re.sub(r"(?i)\.zip$", "", file_name)
    return re.sub(r"[_]+", " ", stem).strip()


def pick_hires(names):
    """Matches the app: the hires.txt closest to the zip root wins."""
    candidates = [n for n in names if n.replace("\\", "/").lower().split("/")[-1] == "hires.txt"]
    if not candidates:
        return None
    return min(candidates, key=lambda n: (n.replace("\\", "/").count("/"), len(n)))


def sha256_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def describe_pack(path: str):
    with zipfile.ZipFile(path) as zf:
        hires = pick_hires(zf.namelist())
        if hires is None:
            return None
        text = zf.read(hires).decode("utf-8", errors="replace")
    roms = sorted({m.upper() for m in SUPPORTED_ROM.findall(text)})
    return roms


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("packs_dir")
    parser.add_argument("out_dir")
    parser.add_argument("--repo", default="izzy2lost/NESHD-HDPacks")
    parser.add_argument("--tag", default="packs-v1")
    args = parser.parse_args()

    os.makedirs(os.path.join(args.out_dir, "assets"), exist_ok=True)
    catalog_path = os.path.join(args.out_dir, "hdpacks.json")

    previous = {}
    if os.path.isfile(catalog_path):
        with open(catalog_path, encoding="utf-8") as f:
            for item in json.load(f).get("packs", []):
                previous[item.get("file")] = item

    packs = []
    seen_assets = set()
    for file_name in sorted(os.listdir(args.packs_dir), key=str.lower):
        if not file_name.lower().endswith(".zip"):
            continue
        src = os.path.join(args.packs_dir, file_name)
        roms = describe_pack(src)
        if roms is None:
            print(f"skip (no hires.txt): {file_name}", file=sys.stderr)
            continue

        name = asset_name(file_name)
        if name in seen_assets:
            print(f"error: asset name collision {name} ({file_name})", file=sys.stderr)
            return 1
        seen_assets.add(name)

        dst = os.path.join(args.out_dir, "assets", name)
        if not os.path.exists(dst):
            try:
                os.link(src, dst)
            except OSError:
                shutil.copy2(src, dst)

        old = previous.get(name, {})
        packs.append({
            "id": name.removesuffix(".zip").lower(),
            "title": old.get("title") or default_title(file_name),
            "aliases": old.get("aliases", []),
            "notes": old.get("notes", ""),
            "file": name,
            "url": f"https://github.com/{args.repo}/releases/download/{args.tag}/{name}",
            "size": os.path.getsize(src),
            "sha256": sha256_file(src),
            "romSha1": roms,
        })
        print(f"{name:55} {os.path.getsize(src) / 1e6:8.1f} MB  roms={len(roms)}")

    with open(catalog_path, "w", encoding="utf-8") as f:
        json.dump({"version": 1, "packs": packs}, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"\nWrote {len(packs)} packs to {catalog_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
