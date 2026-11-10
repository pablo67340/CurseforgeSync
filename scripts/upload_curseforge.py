#!/usr/bin/env python3
"""Upload every CurseforgeSync jar to CurseForge as project files, with the
right game version, modloader, Java version and environment tags, plus the
changelog.

Four files go up per release:
  universal  one jar for Forge 1.19.2+, NeoForge, Fabric and Quilt, tagged with
             every Minecraft release CurseForge lists from 1.19.2 upward, so a
             new Minecraft version is covered without building anything
  1.16.5     ModLauncher 8
  1.12.2     LaunchWrapper coremod
  1.7.10     LaunchWrapper coremod

Prerequisites:
  1. pip install -r requirements.txt          (just `requests`)
  2. An API token from https://authors.curseforge.com/account/api-tokens
     -> put it in the CURSEFORGE_TOKEN environment variable (or --token)
  3. Your project's numeric ID (shown in the "About Project" box on the
     project page) -> CURSEFORGE_PROJECT_ID environment variable (or
     --project-id)
  4. The jars built and collected into build/libs by `gradlew release`

Usage, from the project root:
  python scripts/upload_curseforge.py --project-id 123456
  python scripts/upload_curseforge.py --dry-run          # resolve + preview only
  python scripts/upload_curseforge.py --only universal,1.12.2
"""

import argparse
import json
import os
import re
import sys
from pathlib import Path

import requests

PROJECT_ROOT = Path(__file__).resolve().parent.parent
UPLOAD_API = "https://minecraft.curseforge.com/api"

MOD_NAME = "CurseforgeSync"
JAR_PREFIX = "curseforgesync"

# The universal jar never touches a Minecraft class and detects the version at
# launch, so it is tagged with every release from the oldest one its loaders
# support. Forge 1.19.2 (ModLauncher 10) is that floor.
UNIVERSAL_MIN_MC = (1, 19, 2)
UNIVERSAL_LOADERS = ["Forge", "NeoForge", "Fabric", "Quilt"]
UNIVERSAL_JAVA = ["Java 17", "Java 21", "Java 25"]

# One jar per legacy Minecraft version, plus the Java that version of the game
# runs on. This is the runtime Java, not the level the jar is compiled to.
LEGACY_PORTS = [
    {"mc": "1.7.10", "java": "Java 8"},
    {"mc": "1.12.2", "java": "Java 8"},
    {"mc": "1.16.5", "java": "Java 8"},
]

# Server only. The mod does run on a client, but syncing a mods folder against a
# pack is a server job, and tagging it Client would put it in front of people
# looking for something to install alongside a pack rather than to serve one.
ENVIRONMENT_TAG = ("environment", "Server")

RELEASE_VERSION = re.compile(r"\d+(\.\d+)+")


def read_mod_version() -> str:
    for line in (PROJECT_ROOT / "gradle.properties").read_text().splitlines():
        if line.strip().startswith("mod_version="):
            return line.split("=", 1)[1].strip()
    sys.exit("mod_version not found in gradle.properties")


def read_changelog(path: Path, version: str) -> str:
    """Just the section for this release, not the whole file.

    Every uploaded file gets its own changelog on CurseForge, so pasting the
    entire history onto each one would bury what actually changed.
    """
    text = path.read_text(encoding="utf-8")
    heading = re.compile(r"^##\s+v?" + re.escape(version) + r"\s*$", re.MULTILINE)
    start = heading.search(text)
    if not start:
        print(f"  note: no '## {version}' section in {path.name}, using the whole file")
        return text
    following = re.compile(r"^##\s+", re.MULTILINE).search(text, start.end())
    return text[start.end():following.start() if following else len(text)].strip()


def fetch_version_ids(token: str) -> dict:
    """Map (category, display name) to numeric game-version IDs.

    The same display name can exist under several version types (e.g.
    "1.19.2" as a Minecraft version and under an addon category), and the
    upload API rejects IDs from the wrong type, so the type must be part of
    the lookup key.
    """
    headers = {"X-Api-Token": token}
    types_resp = requests.get(f"{UPLOAD_API}/game/version-types",
                              headers=headers, timeout=30)
    types_resp.raise_for_status()
    type_category = {}
    for vt in types_resp.json():
        name = vt["name"]
        # Minecraft version types are usually "Minecraft 1.19", but some are
        # bare version numbers like "26.2"
        if name.startswith("Minecraft") or RELEASE_VERSION.fullmatch(name):
            type_category[vt["id"]] = "minecraft"
        elif name == "Java":
            type_category[vt["id"]] = "java"
        elif name in ("Modloader", "ModLoader"):
            type_category[vt["id"]] = "modloader"
        elif name == "Environment":
            type_category[vt["id"]] = "environment"

    resp = requests.get(f"{UPLOAD_API}/game/versions", headers=headers, timeout=30)
    resp.raise_for_status()
    ids = {}
    for entry in resp.json():
        category = type_category.get(entry["gameVersionTypeID"])
        if category is not None:
            ids[(category, entry["name"])] = entry["id"]
    return ids


def version_tuple(name: str) -> tuple:
    return tuple(int(part) for part in name.split("."))


def minecraft_releases_from(floor: tuple, version_ids: dict) -> list:
    """Every Minecraft release CurseForge knows of at or above `floor`.

    Snapshots and other non-numeric names are skipped, so this only ever
    tags versions a server can actually run.
    """
    releases = [name for category, name in version_ids
                if category == "minecraft" and RELEASE_VERSION.fullmatch(name)
                and version_tuple(name) >= floor]
    return sorted(set(releases), key=version_tuple)


def resolve_tags(tags: list, version_ids: dict) -> list:
    """tags is a list of (category, name) pairs."""
    resolved = []
    for category, name in tags:
        key = (category, name)
        if key not in version_ids:
            close = sorted(n for c, n in version_ids
                           if c == category and name.split()[0] in n)[:10]
            sys.exit(f"CurseForge has no {category} version named {name!r}.\n"
                     f"Closest available names in that category: {close}")
        resolved.append(version_ids[key])
    return resolved


def build_uploads(mod_version: str, jar_dir: Path, version_ids: dict) -> list:
    universal_mc = minecraft_releases_from(UNIVERSAL_MIN_MC, version_ids)
    uploads = [{
        "key": "universal",
        "jar": jar_dir / f"{JAR_PREFIX}-{mod_version}.jar",
        "display": f"{MOD_NAME} {mod_version} (Forge, NeoForge, Fabric, Quilt)",
        "tags": ([("minecraft", mc) for mc in universal_mc]
                 + [("modloader", loader) for loader in UNIVERSAL_LOADERS]
                 + [("java", java) for java in UNIVERSAL_JAVA]
                 + [ENVIRONMENT_TAG]),
    }]
    for port in LEGACY_PORTS:
        uploads.append({
            "key": port["mc"],
            "jar": jar_dir / f"{JAR_PREFIX}-{port['mc']}-{mod_version}.jar",
            "display": f"{MOD_NAME} {mod_version} (MC {port['mc']})",
            "tags": [("minecraft", port["mc"]), ("java", port["java"]),
                     ("modloader", "Forge"), ENVIRONMENT_TAG],
        })
    return uploads


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--project-id", default=os.environ.get("CURSEFORGE_PROJECT_ID"),
                        help="numeric CurseForge project id")
    parser.add_argument("--token", default=os.environ.get("CURSEFORGE_TOKEN"),
                        help="CurseForge author API token")
    parser.add_argument("--mod-version", default=None,
                        help="defaults to mod_version from gradle.properties")
    parser.add_argument("--changelog-file", default=str(PROJECT_ROOT / "CHANGELOG.md"))
    parser.add_argument("--jar-dir", default=None,
                        help="where the built jars are (default: build/libs)")
    parser.add_argument("--release-type", default="release",
                        choices=["release", "beta", "alpha"])
    parser.add_argument("--only", default=None,
                        help="comma-separated uploads: universal and/or legacy MC versions (default: all)")
    parser.add_argument("--dry-run", action="store_true",
                        help="resolve everything and show what would be uploaded")
    args = parser.parse_args()

    if not args.token:
        sys.exit("No API token: set CURSEFORGE_TOKEN or pass --token")
    if not args.project_id and not args.dry_run:
        sys.exit("No project id: set CURSEFORGE_PROJECT_ID or pass --project-id")

    mod_version = args.mod_version or read_mod_version()
    changelog = read_changelog(Path(args.changelog_file), mod_version)
    only = set(args.only.split(",")) if args.only else None
    jar_dir = Path(args.jar_dir) if args.jar_dir else PROJECT_ROOT / "build" / "libs"

    print("Resolving CurseForge game version IDs...")
    version_ids = fetch_version_ids(args.token)
    uploads = [u for u in build_uploads(mod_version, jar_dir, version_ids)
               if only is None or u["key"] in only]

    # Verify all jars exist before uploading anything
    for upload in uploads:
        if not upload["jar"].is_file():
            sys.exit(f"Missing jar: {upload['jar']}\n"
                     f"Run `gradlew release` to build and collect every jar first.")

    uploaded = []
    for upload in uploads:
        game_versions = resolve_tags(upload["tags"], version_ids)
        metadata = {
            "changelog": changelog,
            "changelogType": "markdown",
            "displayName": upload["display"],
            "gameVersions": game_versions,
            "releaseType": args.release_type,
        }
        print(f"\n{upload['jar'].name}")
        print(f"  tags: {', '.join(n for _, n in upload['tags'])}")
        if args.dry_run:
            continue

        with open(upload["jar"], "rb") as fh:
            resp = requests.post(
                f"{UPLOAD_API}/projects/{args.project_id}/upload-file",
                headers={"X-Api-Token": args.token},
                data={"metadata": json.dumps(metadata)},
                files={"file": (upload["jar"].name, fh, "application/java-archive")},
                timeout=300,
            )
        if resp.status_code != 200:
            sys.exit(f"  UPLOAD FAILED ({resp.status_code}): {resp.text}")
        file_id = resp.json()["id"]
        uploaded.append((upload["key"], file_id))
        print(f"  uploaded -> file id {file_id}")

    if args.dry_run:
        print("\nDry run complete - nothing uploaded.")
    else:
        print(f"\nDone: {len(uploaded)} files uploaded. "
              f"They'll appear once CurseForge approval finishes.")


if __name__ == "__main__":
    main()
