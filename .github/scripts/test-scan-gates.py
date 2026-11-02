# Copyright 2026 Kingsrook, LLC
# SPDX-License-Identifier: Apache-2.0

"""Exercise the exact CI guards using synthetic evidence only."""
import contextlib
import copy
import io
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest


sys.dont_write_bytecode = True


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


codeql = load("check-codeql-sarif")


class SarifTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.report = {"version": "2.1.0", "runs": [{"tool": {"driver": {"name": "CodeQL", "rules": [{"id": "synthetic", "properties": {"security-severity": "8.0"}}]}}, "results": []}]}

    def save(self, report):
        (self.directory / "java.sarif").write_text(json.dumps(report), encoding="utf-8")

    def test_clean_report_with_zero_findings_passes(self):
        self.save(self.report)
        self.assertFalse(codeql.check(self.directory, "java-kotlin", 7))

    def test_missing_report_or_wrong_language_fails(self):
        with self.assertRaises(ValueError):
            codeql.check(self.directory, "java-kotlin", 7)
        self.save(self.report)
        with self.assertRaises(ValueError):
            codeql.check(self.directory, "csharp", 7)

    def test_empty_or_malformed_report_fails(self):
        for text in ["", "{", "null", "[]"]:
            with self.subTest(text=text):
                (self.directory / "java.sarif").write_text(text)
                with self.assertRaises((ValueError, AttributeError)):
                    codeql.check(self.directory, "java-kotlin", 7)

    def test_missing_runs_tool_rules_or_results_fails(self):
        variants = [{"version": "2.1.0"}, {"version": "2.1.0", "runs": []}]
        for field in ["tool", "results"]:
            bad = copy.deepcopy(self.report)
            del bad["runs"][0][field]
            variants.append(bad)
        bad = copy.deepcopy(self.report)
        bad["runs"][0]["tool"]["driver"]["rules"] = []
        variants.append(bad)
        for bad in variants:
            with self.subTest(report=bad):
                self.save(bad)
                with self.assertRaises(ValueError):
                    codeql.check(self.directory, "java-kotlin", 7)

    def test_high_finding_blocks_and_unknown_rule_fails(self):
        self.report["runs"][0]["results"] = [{"ruleId": "synthetic"}]
        self.save(self.report)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertTrue(codeql.check(self.directory, "java-kotlin", 7))
        self.report["runs"][0]["results"] = [{"ruleId": "missing"}]
        self.save(self.report)
        with self.assertRaises(ValueError):
            codeql.check(self.directory, "java-kotlin", 7)

    def test_failed_invocation_and_invalid_threshold_fail(self):
        self.report["runs"][0]["invocations"] = [{"executionSuccessful": False}]
        self.save(self.report)
        with self.assertRaises(ValueError):
            codeql.check(self.directory, "java-kotlin", 7)
        with self.assertRaises(ValueError):
            codeql.check(self.directory, "java-kotlin", float("nan"))


if __name__ == "__main__":
    unittest.main()
