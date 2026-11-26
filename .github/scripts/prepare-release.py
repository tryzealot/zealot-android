"""Select a stable release for a push and write its GitHub release notes."""

import argparse
import os
from pathlib import Path
import re
import subprocess


VERSION = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
FIRST_RELEASE_BASE = "e8a168e75f552a133111ffc3bd320967e04c1972"
REPOSITORY = "tryzealot/zealot-android"


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def version_at(commit):
    text = git("show", f"{commit}:version.properties")
    values = re.findall(r"^sdkVersion[ \t]*=[ \t]*(\S+)[ \t]*$", text, re.MULTILINE)
    if len(values) != 1 or not re.fullmatch(VERSION, values[0]):
        raise SystemExit(f"Invalid sdkVersion at {commit}. Expected X.Y.Z.")
    return values[0]


def version_key(version):
    return tuple(map(int, version.split(".")))


def changelog_entry(version):
    changelog = git("show", "HEAD:CHANGELOG.md")
    sections = re.split(r"^## ", changelog, flags=re.MULTILINE)[1:]
    entries = [section.partition("\n")[2].strip()
               for section in sections if section.partition("\n")[0].strip() == version]
    if len(entries) != 1 or not entries[0]:
        raise SystemExit(f"CHANGELOG.md must contain one non-empty '## {version}' section.")
    return re.sub(r"^### ", "## ", entries[0], flags=re.MULTILINE)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", help="The commit at the branch tip before this push")
    parser.add_argument("notes", type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.before):
        parser.error("before must be a full Git commit SHA")

    version = version_at("HEAD")
    old_version = version_at(args.before)
    added_script = git("diff", "--diff-filter=A", "--name-only", args.before, "HEAD",
                       "--", ".github/scripts/prepare-release.py")
    first_release = version == "0.3.1" and bool(added_script)
    if old_version == version and not first_release:
        print(f"sdkVersion is still {version}. No release requested.")
        return
    if version_key(version) < version_key(old_version):
        raise SystemExit("sdkVersion must increase for an automatic release.")

    tag = f"v{version}"
    tags = git("tag", "--list", "v*").splitlines()
    if tag in tags and git("rev-parse", f"refs/tags/{tag}^{{commit}}") != git("rev-parse", "HEAD"):
        raise SystemExit(f"Refusing to move {tag}: it points to another commit.")

    ancestors = git("tag", "--merged", "HEAD", "--list", "v*").splitlines()
    older_tags = [name for name in ancestors if re.fullmatch(f"v{VERSION}", name)
                  and version_key(name[1:]) < version_key(version)]
    base = max(older_tags, key=lambda name: version_key(name[1:]), default=FIRST_RELEASE_BASE)
    comparison = f"https://github.com/{REPOSITORY}/compare/{base}...{tag}"
    notes = changelog_entry(version)
    args.notes.write_text(
        f"{notes}\n\n## Installation\n\n"
        "Add JitPack (`https://jitpack.io`) to your Gradle repositories, then add the SDK dependency.\n\n"
        f'```kotlin\nimplementation("com.github.tryzealot:zealot-android:{tag}")\n```\n\n'
        f"[Full Changelog]({comparison})\n",
        encoding="utf-8",
    )
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as outputs:
        outputs.write(f"tag={tag}\ncreate_tag={'false' if tag in tags else 'true'}\n")
    print(f"Prepared {tag} from {git('rev-parse', 'HEAD')}.")


if __name__ == "__main__":
    main()
