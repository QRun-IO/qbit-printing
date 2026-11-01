# Copyright 2026 Kingsrook, LLC
# SPDX-License-Identifier: Apache-2.0

"""Fail closed unless scanners cover Maven's selected UI-host runtime graph."""

import argparse
import json
import re
from collections import Counter
from pathlib import Path
from typing import TypedDict, cast
from urllib.parse import unquote, urlsplit


class Bom(TypedDict, total=False):
   """Describe the selected CycloneDX coordinate fields."""

   bomFormat: str
   components: list[dict[str, str]]
   metadata: dict[str, dict[str, str]]


class TrivyReport(TypedDict, total=False):
   """Describe package coordinates, leaving runtime shape checks intact."""

   Results: list[dict[str, list[dict[str, str]]]]


class OsvReport(TypedDict, total=False):
   """Keep result bodies untrusted until their explicit validation."""

   results: list[object]


class OsvResult(TypedDict, total=False):
   """Describe source identity and packages after checking the object shape."""

   source: dict[str, str]
   packages: list[object]


class OsvEntry(TypedDict, total=False):
   """Describe the coordinate and optional vulnerability array being checked."""

   package: dict[str, str]
   vulnerabilities: object


REQUIRED = {
   "com.kingsrook.qqq:qqq-frontend-next",
   "com.kingsrook.qqq:qqq-backend-core",
   "com.kingsrook.qqq:qqq-backend-module-filesystem",
   "com.google.guava:guava",
   "com.h2database:h2",
}
REVIEWED_H2_VERSION = "2.4.240"
OMITTED = object()


def resolved(text: str) -> set[tuple[str, str]]:
   """Read Maven's selected runtime JARs and enforce the reviewed H2 pin."""
   packages: set[tuple[str, str]] = set()
   for raw_line in text.splitlines():
      line = re.sub(r"\x1b\[[0-9;]*m", "", raw_line).strip().split(" -- ", 1)[0].strip()
      if not line or line == "The following files have been resolved:":
         continue
      parts = line.split(":")
      if len(parts) not in (5, 6) or parts[-1] not in ("compile", "runtime"):
         raise ValueError(f"Unexpected Maven runtime coordinate: {line}")
      if not all(parts) or parts[2] != "jar":
         raise ValueError(f"Invalid runtime JAR coordinate: {line}")
      name = parts[0] + ":" + parts[1]
      if parts[0].startswith(("org.junit", "org.opentest4j", "org.apiguardian")):
         raise ValueError("Test library in runtime inventory")
      packages.add((name, parts[-2]))
   if not REQUIRED.issubset({name for name, _ in packages}):
      raise ValueError("Maven inventory is empty or lacks required UI-host dependencies")
   # The pinned dependency-review license exception matcher ignores versions.
   # Keep any H2 metadata exception limited to the reviewed, unmodified runtime.
   if {version for name, version in packages if name == "com.h2database:h2"} != {REVIEWED_H2_VERSION}:
      raise ValueError("H2 runtime must match reviewed version " + REVIEWED_H2_VERSION)
   return packages


def components(report: object) -> set[tuple[str, str]]:
   """Extract exact Maven coordinates from the scanner's CycloneDX inventory."""
   typed_report = cast(Bom, report)
   if not isinstance(report, dict) or typed_report.get("bomFormat") != "CycloneDX":
      raise ValueError("Expected CycloneDX inventory")
   packages: set[tuple[str, str]] = set()
   for component in typed_report.get("components", []):
      purl = urlsplit(component.get("purl", ""))
      if purl.scheme != "pkg" or not purl.path.startswith("maven/"):
         raise ValueError("Missing Maven package URL")
      coordinate, version = purl.path.removeprefix("maven/").rsplit("@", 1)
      group, artifact = coordinate.split("/", 1)
      packages.add((unquote(group) + ":" + unquote(artifact), unquote(version)))
   if not packages:
      raise ValueError("Empty CycloneDX inventory")
   return packages


def check(expected: set[tuple[str, str]], bom: object, trivy: object = OMITTED) -> int:
   """Require exact SBOM coverage and, when supplied, selected Trivy coverage."""
   actual = components(bom)
   if actual != expected:
      raise ValueError(f"Maven/SBOM mismatch: missing={sorted(expected - actual)}, extra={sorted(actual - expected)}")
   if trivy is not OMITTED:
      typed_trivy = cast(TrivyReport, trivy)
      if not isinstance(trivy, dict) or not isinstance(typed_trivy.get("Results"), list):
         raise ValueError("Expected Trivy report object with Results array")
      scanned = {(p["Name"], p["Version"]) for r in typed_trivy.get("Results", []) for p in r.get("Packages", [])}
      # Trivy also reports the root project represented by SBOM metadata.
      root = cast(Bom, bom).get("metadata", {}).get("component", {})
      allowed = expected | {(root.get("group", "") + ":" + root.get("name", ""), root.get("version", ""))}
      if not expected.issubset(scanned) or not scanned.issubset(allowed):
         raise ValueError(f"Maven/Trivy mismatch: missing={sorted(expected - scanned)}, extra={sorted(scanned - allowed)}")
   return len(expected)


def check_osv(expected: set[tuple[str, str]], report: object) -> int:
   """Reject missing, ambiguous, malformed or partial OSV package evidence."""
   typed_report = cast(OsvReport, report)
   if not isinstance(report, dict) or not isinstance(typed_report.get("results"), list):
      raise ValueError("Expected OSV report object with results array")
   # OSV 2.6.0 queries CycloneDX purls but omits Maven groups in JSON names:
   # https://github.com/google/osv-scanner/issues/3099
   # Fail on ambiguous display coordinates instead of claiming full identity proof.
   projected = Counter((name.split(":", 1)[1], version) for name, version in expected)
   if any(count != 1 for count in projected.values()):
      raise ValueError("Ambiguous Maven coordinates in pinned OSV report format")
   scanned: Counter[tuple[str, str]] = Counter()
   for raw_result in cast(list[object], typed_report.get("results")):
      result = cast(OsvResult, raw_result)
      if not isinstance(raw_result, dict) or not isinstance(result.get("source"), dict):
         raise ValueError("Missing OSV source")
      source = cast(dict[str, str], result.get("source"))
      if source.get("type") != "sbom" or not isinstance(source.get("path"), str):
         raise ValueError("Expected OSV SBOM source")
      if not isinstance(result.get("packages"), list):
         raise ValueError("Missing OSV package inventory; use --all-packages")
      for raw_entry in cast(list[object], result.get("packages")):
         entry = cast(OsvEntry, raw_entry)
         if not isinstance(raw_entry, dict) or not isinstance(entry.get("package"), dict):
            raise ValueError("Malformed OSV package")
         package = cast(dict[str, str], entry.get("package"))
         if package.get("ecosystem") != "Maven" or not all(isinstance(package.get(k), str) and package[k] for k in ("name", "version")):
            raise ValueError("Invalid OSV Maven package coordinate")
         if "vulnerabilities" in entry and not isinstance(entry["vulnerabilities"], list):
            raise ValueError("Malformed OSV vulnerabilities")
         scanned[(package["name"], package["version"])] += 1
   if scanned != projected:
      raise ValueError("OSV --all-packages inventory differs from selected Maven graph")
   return len(expected)


def main() -> None:
   """Validate the exact evidence files supplied by the required CI job."""
   parser = argparse.ArgumentParser()
   parser.add_argument("resolved", type=Path)
   parser.add_argument("bom", type=Path)
   parser.add_argument("trivy", type=Path, nargs="?")
   parser.add_argument("--osv", type=Path)
   args = parser.parse_args()
   expected = resolved(args.resolved.read_text(encoding="utf-8"))
   count = check(expected, json.loads(args.bom.read_text(encoding="utf-8")), json.loads(args.trivy.read_text(encoding="utf-8")) if args.trivy else OMITTED)
   if args.osv:
      check_osv(expected, json.loads(args.osv.read_text(encoding="utf-8")))
   print(f"Verified {count} Maven runtime dependencies, including the UI frontend.")


if __name__ == "__main__":
   main()
