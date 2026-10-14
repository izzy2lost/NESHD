#!/usr/bin/env python3
"""Refresh the cover manifests bundled in assets so cover lookup works offline."""
import argparse
import json
import urllib.request
from pathlib import Path

REPOS = ("nes_covers", "nes_covers_jp", "fds_covers_jp")
BASE_URL = "https://raw.githubusercontent.com/izzy2lost/{repo}/main/manifest.json"


def slim_manifest(manifest: dict) -> dict:
    items = []
    for item in manifest["items"]:
        entry = {
            "title": item.get("title", ""),
            "path": item.get("path") or item.get("file", ""),
        }
        if item.get("aliases"):
            entry["aliases"] = item["aliases"]
        items.append(entry)
    return {"items": items}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--out",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "app/src/main/assets/covers",
    )
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    for repo in REPOS:
        with urllib.request.urlopen(BASE_URL.format(repo=repo), timeout=30) as response:
            manifest = json.load(response)
        slim = slim_manifest(manifest)
        out_path = args.out / f"{repo}.json"
        out_path.write_text(
            json.dumps(slim, ensure_ascii=False, separators=(",", ":")),
            encoding="utf-8",
        )
        print(f"{repo}: {len(slim['items'])} covers -> {out_path}")


if __name__ == "__main__":
    main()
