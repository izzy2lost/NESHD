#!/usr/bin/env python3
import argparse
import json
import xml.etree.ElementTree as ET
from pathlib import Path


def generate_hash_map(
    source: Path,
    *,
    extensions: set[str] | None = None,
) -> dict[str, str]:
    sha_to_title: dict[str, str] = {}
    current_title: str | None = None
    current_archive_title: str | None = None
    current_in_game = False
    wanted_extensions = {ext.lower() for ext in extensions} if extensions else None

    for event, elem in ET.iterparse(source, events=("start", "end")):
        if event == "start" and elem.tag == "game":
            current_in_game = True
            current_title = elem.attrib.get("name", "").strip()
            current_archive_title = None
        elif event == "start" and elem.tag == "archive" and current_in_game:
            name = elem.attrib.get("name", "").strip()
            if name:
                current_archive_title = name
        elif event == "start" and elem.tag == "file" and current_in_game:
            if elem.attrib.get("bad") == "1":
                continue
            extension = elem.attrib.get("extension", "").strip().lower()
            if wanted_extensions is not None and extension not in wanted_extensions:
                continue
            sha1 = elem.attrib.get("sha1", "").strip().upper()
            if len(sha1) != 40:
                continue
            title = (current_archive_title or current_title or "").strip()
            if title:
                sha_to_title.setdefault(sha1, title)
        elif event == "end" and elem.tag == "game":
            current_in_game = False
            current_title = None
            current_archive_title = None
            elem.clear()

    return dict(sorted(sha_to_title.items()))


def read_existing_json(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate the compact NES SHA-1 title map asset from a No-Intro XML file."
    )
    parser.add_argument("xml", type=Path, help="Path to the No-Intro NES XML file")
    parser.add_argument(
        "--fds-xml",
        type=Path,
        help="Optional No-Intro Family Computer Disk System XML file to merge as hashes_fds",
    )
    parser.add_argument(
        "--merge-existing",
        action="store_true",
        help="Preserve existing optional maps such as hashes_jp while updating the requested maps",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=Path("app/src/main/assets/NesRomDb.json"),
        help="Output asset path, relative to Android/ by default",
    )
    args = parser.parse_args()

    hashes = generate_hash_map(args.xml)
    existing = read_existing_json(args.out) if args.merge_existing else {}
    payload = {
        "format": "neshd-no-intro-sha1-title-map-v1",
        "source": args.xml.name,
        "hashCount": len(hashes),
        "hashes": hashes,
    }

    for key in ("sourceJp", "hashCountJp", "hashes_jp"):
        if key in existing:
            payload[key] = existing[key]

    if args.fds_xml:
        hashes_fds = generate_hash_map(args.fds_xml, extensions={"fds", "qd"})
        payload.update(
            {
                "sourceFds": args.fds_xml.name,
                "hashCountFds": len(hashes_fds),
                "hashes_fds": hashes_fds,
            }
        )
    else:
        for key in ("sourceFds", "hashCountFds", "hashes_fds"):
            if key in existing:
                payload[key] = existing[key]

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(payload, separators=(",", ":"), ensure_ascii=True),
        encoding="utf-8",
    )
    print(f"Wrote {args.out} with {len(hashes)} hashes")
    if args.fds_xml:
        print(f"Added hashes_fds with {len(hashes_fds)} hashes")


if __name__ == "__main__":
    main()
