"""Check the published SDK's public JVM signatures against a new Android AAR."""

import argparse
import re
import subprocess
import tempfile
from pathlib import Path
from zipfile import ZipFile

INTERNAL_MEMBER = re.compile(r"\baccess\$|\$zealot(?:\$|\()")
CLASS_NAME = re.compile(r"\b(?:class|interface) ([^\s<]+)")


def read_api(aar, classes):
    with tempfile.TemporaryDirectory() as directory, ZipFile(aar) as archive:
        jar = Path(directory) / "classes.jar"
        jar.write_bytes(archive.read("classes.jar"))
        with ZipFile(jar) as class_files:
            entries = set(class_files.namelist())
        present = [name for name in classes if name.replace(".", "/") + ".class" in entries]
        if not present:
            return set()

        output = subprocess.run(
            ["javap", "-protected", "-s", "-classpath", str(jar), *present],
            check=True,
            capture_output=True,
            text=True,
        ).stdout

    api = set()
    for block in output.split("\n}"):
        lines = block.strip().splitlines()
        if not lines:
            continue
        header = lines[1].removesuffix(" {")
        if not header.startswith(("public ", "protected ")):
            continue
        declaration = CLASS_NAME.search(header)
        if not declaration:
            raise ValueError(f"Unexpected javap declaration: {header}")
        name = declaration.group(1)
        api.add(f"{name} | {header}")
        members = [line.strip() for line in lines[2:] if line.strip()]
        for signature, descriptor in zip(members[::2], members[1::2], strict=True):
            if not descriptor.startswith("descriptor: "):
                raise ValueError(f"Unexpected javap output for {name}: {descriptor}")
            if INTERNAL_MEMBER.search(signature):
                continue
            # Kotlin generates these two access constructors for private SDK constructors.
            if signature in {
                "public im.ews.zealot.Zealot(android.content.Context, kotlin.jvm.internal.DefaultConstructorMarker);",
                "public im.ews.zealot.Zealot$Companion(kotlin.jvm.internal.DefaultConstructorMarker);",
            }:
                continue
            api.add(f"{name} | {signature} {descriptor.removeprefix('descriptor: ')}")
    return api


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aar", type=Path)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--baseline", type=Path)
    mode.add_argument("--snapshot", metavar="CLASS", nargs="+", help="Print signatures for these public classes")
    args = parser.parse_args()

    if args.snapshot:
        api = read_api(args.aar, args.snapshot)
        missing_classes = set(args.snapshot) - {line.split(" | ", 1)[0] for line in api}
        if missing_classes:
            parser.error("public classes missing from the AAR: " + ", ".join(sorted(missing_classes)))
        print("\n".join(sorted(api)))
        return

    expected = {line for line in args.baseline.read_text().splitlines() if line and not line.startswith("#")}
    if not expected:
        parser.error("the API baseline is empty")
    classes = sorted({line.split(" | ", 1)[0] for line in expected})
    missing = expected - read_api(args.aar, classes)
    if missing:
        print("Missing or changed public SDK signatures:")
        print("\n".join(sorted(missing)))
        raise SystemExit(1)
    print(f"Verified {len(expected)} public JVM signatures against {args.baseline.name}.")


if __name__ == "__main__":
    main()
