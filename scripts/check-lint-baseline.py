#!/usr/bin/env python3
"""Allow baseline removals and line movement, but reject newly suppressed lint issues."""

from collections import Counter
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET


BASELINE = "app/lint-baseline.xml"


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


def issues(xml):
    # Line/column numbers move during normal edits; message and file identify the debt.
    return Counter(
        (issue.attrib["id"], issue.attrib["message"],
         tuple(sorted(location.attrib["file"] for location in issue.findall("location"))))
        for issue in ET.fromstring(xml).findall("issue")
    )


def main():
    root = subprocess.check_output(
        ["git", "rev-parse", "--show-toplevel"], text=True
    ).strip()
    os.chdir(root)
    reference = reference_commit()
    previous = subprocess.check_output(["git", "show", f"{reference}:{BASELINE}"])
    added = issues(Path(BASELINE).read_bytes()) - issues(previous)
    if added:
        # Never echo diagnostic messages or source snippets: they may contain private data.
        for (rule, _, files), count in sorted(added.items()):
            print(f"New lint suppression: {rule} ({count}) in {', '.join(files)}", file=sys.stderr)
        print("Fix the finding instead of expanding the lint baseline.", file=sys.stderr)
        return 1
    print("Lint baseline has no new suppressions.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
