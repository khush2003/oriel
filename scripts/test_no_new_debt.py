"""Regression checks for the no-new-debt policy."""

import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
from subprocess import CompletedProcess


spec = importlib.util.spec_from_file_location(
    "baseline", Path(__file__).with_name("check-no-new-debt.py")
)
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)


def document(line=1, rule="NewApi", message="Requires API 33", file="src/Example.kt"):
    return (f'<issues><issue id="{rule}" message="{message}">'
            f'<location file="{file}" line="{line}" column="1"/>'
            '</issue></issues>')


def smell(entry):
    return f"<SmellBaseline><CurrentIssues><ID>{entry}</ID></CurrentIssues></SmellBaseline>"


class BaselineTest(unittest.TestCase):
    def test_explicit_ci_base_takes_precedence(self):
        with patch.dict(baseline.os.environ, {"BASE_REF": "pr-base"}), patch.object(baseline.subprocess, "run") as git:
            self.assertEqual(baseline.reference_commit(), "pr-base")
            git.assert_not_called()

    def test_committed_branch_changes_compare_with_merge_base(self):
        with patch.dict(baseline.os.environ, {"BASE_REF": ""}), patch.object(
            baseline.subprocess, "run", return_value=CompletedProcess([], 0, "branch-point\n")
        ) as git:
            self.assertEqual(baseline.reference_commit(), "branch-point")
            self.assertEqual(git.call_args.args[0], ["git", "merge-base", "HEAD", "origin/main"])

    def test_missing_remote_falls_back_with_visible_warning(self):
        with patch.dict(baseline.os.environ, {"BASE_REF": ""}), patch.object(
            baseline.subprocess, "run", return_value=CompletedProcess([], 1, "")
        ), patch("sys.stderr") as stderr:
            self.assertEqual(baseline.reference_commit(), "HEAD")
            self.assertTrue(stderr.write.called)

    def test_line_movement_is_allowed(self):
        self.assertFalse(baseline.issues(document(20)) - baseline.issues(document()))

    def test_removing_debt_is_allowed(self):
        self.assertFalse(baseline.issues("<issues/>") - baseline.issues(document()))

    def test_new_rule_message_or_file_is_rejected(self):
        for change in ({"rule": "UnusedResources"}, {"message": "Requires API 34"},
                       {"file": "src/Other.kt"}):
            with self.subTest(change=change):
                self.assertTrue(baseline.issues(document(**change)) - baseline.issues(document()))

    def test_duplicate_suppression_is_rejected(self):
        previous = baseline.issues(document())
        doubled = document().replace("</issues>", document().replace("<issues>", ""))
        self.assertEqual(sum((baseline.issues(doubled) - previous).values()), 1)

    def test_a_finding_that_moved_to_another_file_is_allowed(self):
        previous = baseline.smells(smell("LongMethod:Player.kt$fun play()"))
        self.assertFalse(baseline.smells(smell("LongMethod:Controls.kt$fun play()")) - previous)

    def test_a_new_finding_is_rejected(self):
        previous = baseline.smells(smell("LongMethod:Player.kt$fun play()"))
        for entry in ("LongMethod:Player.kt$fun pause()", "ComplexCondition:Player.kt$fun play()"):
            with self.subTest(entry=entry):
                self.assertTrue(baseline.smells(smell(entry)) - previous)

    def test_only_an_oversized_file_that_grew_fails(self):
        limit = baseline.MAX_LINES
        self.assertFalse(baseline.grew(limit, 0))
        self.assertTrue(baseline.grew(limit + 1, 0))
        self.assertFalse(baseline.grew(limit + 200, limit + 200))
        self.assertFalse(baseline.grew(limit + 100, limit + 200))
        self.assertTrue(baseline.grew(limit + 201, limit + 200))


if __name__ == "__main__":
    unittest.main()
