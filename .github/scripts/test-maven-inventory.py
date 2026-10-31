"""Synthetic regressions for missing, incorrect and empty scanner inventories."""
import copy
import importlib.util
from pathlib import Path
import sys
import subprocess
import tempfile
import json
import unittest


sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("inventory", Path(__file__).with_name("check-maven-inventory.py"))
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


class InventoryTests(unittest.TestCase):
    def setUp(self):
        self.expected = {(name, "2.4.240" if name == "com.h2database:h2" else "1.0") for name in inventory.REQUIRED} | {("org.jspecify:jspecify", "1.0.0")}
        self.text = "The following files have been resolved:\n" + "\n".join(
            f"   {name}:jar:{version}:runtime\x1b[36m -- module example\x1b[m" for name, version in self.expected)
        self.bom = {"bomFormat": "CycloneDX", "components": [
            {"purl": "pkg:maven/" + name.replace(":", "/") + "@" + version + "?type=jar"}
            for name, version in self.expected]}
        self.trivy = {"Results": [{"Packages": [{"Name": n, "Version": v} for n, v in self.expected], "Vulnerabilities": []}]}

    def test_complete_graph_without_findings_passes(self):
        self.assertEqual(self.expected, inventory.resolved(self.text))
        self.assertEqual(6, inventory.check(self.expected, self.bom, self.trivy))

    def test_missing_or_unreviewed_h2_version_fails(self):
        without_h2 = "\n".join(line for line in self.text.splitlines() if "com.h2database:h2:" not in line)
        for text in (without_h2, self.text.replace("2.4.240", "2.3.232"), self.text.replace("2.4.240", "2.4.241"),
                     self.text + "\ncom.h2database:h2:jar:2.3.232:runtime"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                inventory.resolved(text)

    def test_missing_frontend_and_empty_maven_fail(self):
        for text in ("", "\n".join(line for line in self.text.splitlines() if "qqq-frontend-next" not in line)):
            with self.assertRaises(ValueError):
                inventory.resolved(text)

    def test_test_scope_or_test_jar_fails(self):
        for extra in ("example:test:jar:1:test", "org.junit.jupiter:junit-jupiter:jar:5:runtime"):
            with self.assertRaises(ValueError):
                inventory.resolved(self.text + "\n" + extra)

    def test_empty_malformed_or_missing_component_fails(self):
        for bom in ({}, {"bomFormat": "CycloneDX", "components": []}, {"bomFormat": "CycloneDX", "components": [{}]},
                    {"bomFormat": "CycloneDX", "components": self.bom["components"][1:]}):
            with self.assertRaises(ValueError):
                inventory.check(self.expected, bom)

    def test_wrong_selected_version_fails(self):
        bad = copy.deepcopy(self.trivy)
        for package in bad["Results"][0]["Packages"]:
            if package["Name"] == "org.jspecify:jspecify":
                package["Version"] = "1.0.1"
        with self.assertRaises(ValueError):
            inventory.check(self.expected, self.bom, bad)

    def test_empty_or_dropped_trivy_package_fails(self):
        for report in (None, {}, {"Results": []}, {"Results": [{"Packages": self.trivy["Results"][0]["Packages"][1:]}]}):
            with self.assertRaises(ValueError):
                inventory.check(self.expected, self.bom, report)

    def test_cli_supplied_null_trivy_fails(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "resolved.txt").write_text(self.text, encoding="utf-8")
            (directory / "bom.json").write_text(json.dumps(self.bom), encoding="utf-8")
            (directory / "trivy.json").write_text("null", encoding="utf-8")
            result = subprocess.run([sys.executable, str(Path(inventory.__file__)),
                                     str(directory / "resolved.txt"), str(directory / "bom.json"),
                                     str(directory / "trivy.json")], capture_output=True, text=True, check=False)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Expected Trivy report object", result.stderr)

    def osv(self):
        return {"results": [{"source": {"path": "/src/printing.cdx.json", "type": "sbom"}, "packages": [
            {"package": {"name": name.split(":", 1)[1], "version": version, "ecosystem": "Maven"}}
            for name, version in self.expected]}]}

    def test_osv_all_clean_packages_pass_without_vulnerabilities(self):
        self.assertEqual(6, inventory.check_osv(self.expected, self.osv()))

    def test_osv_missing_malformed_or_partial_evidence_fails(self):
        partial = self.osv()
        partial["results"][0]["packages"].pop()
        wrong = self.osv()
        wrong["results"][0]["packages"][0]["package"]["version"] = "wrong"
        duplicate = self.osv()
        duplicate["results"][0]["packages"].append(duplicate["results"][0]["packages"][0])
        for report in (None, {}, [], {"results": None}, {"results": []}, {"results": [{}]}, partial, wrong, duplicate):
            with self.subTest(report=report), self.assertRaises(ValueError):
                inventory.check_osv(self.expected, report)

    def test_osv_ambiguous_group_projection_fails(self):
        with self.assertRaises(ValueError):
            inventory.check_osv(self.expected | {("other:jspecify", "1.0.0")}, self.osv())


if __name__ == "__main__":
    unittest.main()
