#!/usr/bin/env python3
"""Reject new debt: lint or detekt suppressions, and growth of an oversized source file."""

from collections import Counter
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET


LINT = "app/lint-baseline.xml"
DETEKT = "detekt-baseline.xml"
SOURCES = ("app/src/main", "core/src/main")
MAX_LINES = 600


def reference_commit():
    if os.environ.get("BASE_REF"):
        return os.environ["BASE_REF"]
    merge_base = subprocess.run(
        ["git", "merge-base", "HEAD", "origin/main"],
        capture_output=True, text=True,
    )
    if merge_base.returncode == 0:
        return merge_base.stdout.strip()
    print("No merge base with origin/main; checking against HEAD only.", file=sys.stderr)
    return "HEAD"


def at(reference, path):
    shown = subprocess.run(["git", "show", f"{reference}:{path}"], capture_output=True)
    return shown.stdout if shown.returncode == 0 else None


def issues(xml):
    # Line/column numbers move during normal edits; message and file identify the debt.
    return Counter(
        (issue.attrib["id"], issue.attrib["message"],
         tuple(sorted(location.attrib["file"] for location in issue.findall("location"))))
        for issue in ET.fromstring(xml).findall("issue")
    )


def smells(xml):
    # An entry reads "Rule:File.kt$signature". The file is left out, so moved code isn't new debt.
    return Counter(
        (entry.text.split(":", 1)[0], entry.text.split("$", 1)[-1])
        for entry in ET.fromstring(xml).iter("ID")
    )


def grew(lines, before):
    return lines > max(before, MAX_LINES)


def grown_files(reference):
    listed = subprocess.check_output(
        ["git", "ls-files", "-co", "--exclude-standard", "--", *SOURCES], text=True
    ).splitlines()
    for path in listed:
        if not path.endswith(".kt") or not Path(path).is_file():
            continue
        lines = Path(path).read_bytes().count(b"\n")
        before = (at(reference, path) or b"").count(b"\n") if lines > MAX_LINES else 0
        if grew(lines, before):
            yield path, before, lines


def main():
    root = subprocess.check_output(
        ["git", "rev-parse", "--show-toplevel"], text=True
    ).strip()
    os.chdir(root)
    reference = reference_commit()
    failed = False

    # Never echo diagnostic messages or source snippets: they may contain private data.
    previous = subprocess.check_output(["git", "show", f"{reference}:{LINT}"])
    for (rule, _, files), count in sorted((issues(Path(LINT).read_bytes()) - issues(previous)).items()):
        print(f"New lint suppression: {rule} ({count}) in {', '.join(files)}", file=sys.stderr)
        failed = True

    previous = at(reference, DETEKT)
    if previous is not None:
        added = Counter(rule for rule, _ in (smells(Path(DETEKT).read_bytes()) - smells(previous)).elements())
        for rule, count in sorted(added.items()):
            print(f"New detekt suppression: {rule} ({count})", file=sys.stderr)
            failed = True

    for path, before, lines in grown_files(reference):
        was = f"grew from {before} to {lines}" if before else f"is new with {lines}"
        print(f"{path} {was} lines; the limit is {MAX_LINES}. Move code into another file.", file=sys.stderr)
        failed = True

    if failed:
        print("Fix the finding instead of expanding a baseline.", file=sys.stderr)
        return 1
    print("No new suppressions and no oversized file grew.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
