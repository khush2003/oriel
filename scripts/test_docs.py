"""Keep agent entry points and navigation from silently drifting as files move."""

from pathlib import Path
import re
import unittest
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parent.parent
DOCS = ("AGENTS.md", "CONTRIBUTING.md", "docs/ARCHITECTURE.md", "docs/QA.md")


class DocsTest(unittest.TestCase):
    def test_local_link_targets_exist(self):
        for name in DOCS:
            document = ROOT / name
            for link in re.findall(r"\]\(([^)]+)\)", document.read_text()):
                target = urlsplit(link)
                if target.scheme or target.netloc or not target.path:
                    continue
                with self.subTest(document=name, link=link):
                    self.assertTrue((document.parent / unquote(target.path)).exists())

    def test_documented_script_entry_points_exist_and_are_executable(self):
        for name in DOCS:
            for script in re.findall(r"(?:scripts/[\w-]+\.sh|\./gradlew)\b", (ROOT / name).read_text()):
                with self.subTest(document=name, script=script):
                    path = ROOT / script
                    self.assertTrue(path.is_file())
                    self.assertTrue(path.stat().st_mode & 0o111)

    def test_targeted_test_classes_exist_in_the_named_module(self):
        commands = re.findall(
            r":(core|app):\w+ --tests '([\w.]+)'", (ROOT / "docs/QA.md").read_text()
        )
        self.assertTrue(commands, "Keep targeted test commands in QA.md")
        for module, name in commands:
            language = "kotlin" if module == "core" else "java"
            with self.subTest(module=module, test=name):
                self.assertTrue((ROOT / module / "src/test" / language / (name.replace(".", "/") + ".kt")).is_file())


if __name__ == "__main__":
    unittest.main()
