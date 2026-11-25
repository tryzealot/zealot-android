"""Validate a release tag and optionally wait for its JitPack publication."""

import argparse
import json
from pathlib import Path
import re
import time
from urllib.error import URLError
from urllib.request import urlopen
from xml.etree import ElementTree


VERSION_FILE = Path(__file__).resolve().parents[2] / "version.properties"
VERSION_PATTERN = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
TAG_PATTERN = re.compile(rf"v({VERSION_PATTERN})")
ARTIFACT = "zealot-android"
JITPACK = "https://jitpack.io"


def validate_version(tag):
    match = TAG_PATTERN.fullmatch(tag)
    if not match:
        raise SystemExit("Release tags must use the stable vX.Y.Z format.")
    versions = re.findall(r"^sdkVersion\s*=\s*(\S+)[ \t]*$", VERSION_FILE.read_text(), re.MULTILINE)
    if versions != [match.group(1)]:
        raise SystemExit(f"{tag} must match the base sdkVersion in version.properties.")
    print(f"{tag} matches sdkVersion={match.group(1)}.", flush=True)


def read_url(url):
    with urlopen(url, timeout=30) as response:
        return response.read()


def verify_jitpack(owner, tag, commit):
    group = f"com.github.{owner}"
    directory = f"{JITPACK}/com/github/{owner}/{ARTIFACT}/{tag}"
    pom_url = f"{directory}/{ARTIFACT}-{tag}.pom"
    status_url = f"{JITPACK}/api/builds/{group}/{ARTIFACT}/{tag}"
    expected_pom = (group, ARTIFACT, tag, "aar")
    print(f"Waiting for {group}:{ARTIFACT}:{tag}. Build log: {directory}/build.log", flush=True)
    deadline = time.monotonic() + 360
    while time.monotonic() < deadline:
        try:
            # Requesting the POM starts an on-demand build if JitPack has not built the tag yet.
            pom = ElementTree.fromstring(read_url(pom_url))
            actual_pom = tuple(
                pom.findtext(f"{{*}}{field}") for field in ("groupId", "artifactId", "version", "packaging")
            )
            if actual_pom != expected_pom:
                raise SystemExit(f"Unexpected JitPack POM coordinates: {actual_pom!r}")

            build = json.loads(read_url(status_url))
            if build.get("status") == "ok":
                if build.get("commit") != commit or build.get("isTag") is not True:
                    raise SystemExit(f"JitPack did not build {tag} from expected commit {commit}.")
                print(f"Verified JitPack POM and build commit {commit}.", flush=True)
                return
            print(f"JitPack build status: {build.get('status', 'unknown')}", flush=True)
        except (URLError, TimeoutError, ElementTree.ParseError, json.JSONDecodeError) as error:
            print(f"Publication pending: {error}", flush=True)
        time.sleep(15)
    raise SystemExit(f"Timed out waiting for JitPack. See {directory}/build.log.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    parser.add_argument("--jitpack-owner")
    parser.add_argument("--commit")
    args = parser.parse_args()
    if args.jitpack_owner is not None or args.commit is not None:
        if not re.fullmatch(r"[A-Za-z0-9-]+", args.jitpack_owner or ""):
            parser.error("--jitpack-owner must be a GitHub owner")
        if not re.fullmatch(r"[0-9a-f]{40}", args.commit or ""):
            parser.error("--commit must be the full Git commit SHA")
    validate_version(args.tag)
    if args.jitpack_owner:
        verify_jitpack(args.jitpack_owner, args.tag, args.commit)


if __name__ == "__main__":
    main()
