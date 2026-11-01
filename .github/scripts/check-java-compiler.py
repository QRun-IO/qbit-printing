# Copyright (C) 2026 Kingsrook, LLC
# SPDX-License-Identifier: Apache-2.0
"""Exercise the repository's actual compiler configuration on isolated probes."""

import argparse
import copy
import json
import logging
import os
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

NS = "{http://maven.apache.org/POM/4.0.0}"
LOG = logging.getLogger(__name__)
PROBES: dict[str, tuple[str, str | None]] = {
   "positive": ('public String value() { return "present"; }', None),
   "nullaway": ("public String value() { return null; }", "[NullAway]"),
   "errorprone": ("public void value() { new IllegalArgumentException(); }", "[DeadException]"),
   "warnings": ('public long value() { return java.util.Date.parse("2000-01-01"); }', "warnings found and -Werror specified"),
}


@dataclass(frozen=True)
class GuardContext:
   """Everything shared by all probes: repository root, evidence folder, probe POM and Maven command."""

   root: Path
   output: Path
   pom: ET.ElementTree
   command: list[str]


@dataclass(frozen=True)
class Probe:
   """One isolated compilation: source root, name, the member source and the diagnostic it must produce (None means it must compile)."""

   phase: str
   name: str
   source: str
   expected: str | None


def probe_pom(source: ET.Element) -> ET.ElementTree:
   """Copy compiler configuration, including executions, without changing its flags."""
   compiler = source.find(f"{NS}build/{NS}plugins/{NS}plugin[{NS}artifactId='maven-compiler-plugin']")
   if compiler is None:
      raise ValueError("Repository must declare its compiler plugin")
   project = ET.Element(f"{NS}project")
   for name, value in (("modelVersion", "4.0.0"), ("groupId", "com.kingsrook.qbits"), ("artifactId", "compiler-configuration-probe"), ("version", "1")):
      ET.SubElement(project, f"{NS}{name}").text = value
   properties = source.find(f"{NS}properties")
   if properties is not None:
      project.append(copy.deepcopy(properties))
   plugins = ET.SubElement(ET.SubElement(project, f"{NS}build"), f"{NS}plugins")
   plugins.append(copy.deepcopy(compiler))
   return ET.ElementTree(project)


def run_probe(context: GuardContext, probe: Probe) -> dict[str, str | bool]:
   """Require the intended diagnostic, not merely an unrelated Maven failure."""
   folder = context.output / f"{probe.phase}-{probe.name}"
   package = folder / "src" / probe.phase / "java" / "com" / "kingsrook" / "qbits" / "printing"
   package.mkdir(parents=True)
   (package / "Probe.java").write_text(f"// Generated compiler guard fixture; not application source.\npackage com.kingsrook.qbits.printing;\npublic final class Probe {{ {probe.source} }}\n", encoding="utf-8")
   context.pom.write(folder / "pom.xml", encoding="utf-8", xml_declaration=True)
   result = subprocess.run(  # noqa: S603 -- argument list (no shell) of the repository Maven wrapper or the operator-supplied executable plus fixed Maven goals
      [*context.command, "-f", str(folder / "pom.xml"), "test-compile"],
      cwd=context.root,
      capture_output=True,
      text=True,
      timeout=180,
      check=False,
   )
   log = result.stdout + result.stderr
   (folder / "compiler.log").write_text(log, encoding="utf-8")
   passed = result.returncode == 0 if probe.expected is None else result.returncode != 0 and probe.expected in log
   if probe.name == "warnings":
      passed = passed and "[deprecation]" in log
   if not passed:
      raise RuntimeError(f"Compiler guard failed: {probe.phase}/{probe.name}; inspect {folder / 'compiler.log'}")
   return {"sourceRoot": probe.phase, "probe": probe.name, "expectedRejection": probe.expected is not None, "passed": True}


def main() -> None:
   """Run positive and negative main/test probes without installing any artifact."""
   parser = argparse.ArgumentParser(description=__doc__)
   parser.add_argument("--maven", help="Optional exact Maven executable; otherwise use the repository wrapper")
   parser.add_argument("--settings", type=Path)
   parser.add_argument("--local-repository", type=Path)
   parser.add_argument("--offline", action="store_true")
   args = parser.parse_args()
   root = Path(__file__).resolve().parents[2]
   command = [args.maven] if args.maven else ([str(root / "mvnw.cmd")] if os.name == "nt" else ["bash", str(root / "mvnw")])
   command += ["-B", "-ntp"]
   if args.offline:
      command.append("-o")
   if args.settings:
      command += ["-s", str(args.settings.resolve())]
   if args.local_repository:
      command.append(f"-Dmaven.repo.local={args.local_repository.resolve()}")
   output_root = root / "target" / "compiler-guard"
   output_root.mkdir(parents=True, exist_ok=True)
   output = Path(tempfile.mkdtemp(prefix="run-", dir=output_root))
   ET.register_namespace("", NS[1:-1])
   pom = probe_pom(ET.parse(root / "pom.xml").getroot())  # noqa: S314 -- parses this repository's own checked-in pom.xml, never external input
   context = GuardContext(root, output, pom, command)
   results: list[dict[str, str | bool]] = []
   for phase in ("main", "test"):
      for name, (source, expected) in PROBES.items():
         results.append(run_probe(context, Probe(phase, name, source, expected)))
   (output / "results.json").write_text(json.dumps(results, indent=3) + "\n", encoding="utf-8")
   LOG.info("Compiler guard passed", extra={"probeCount": len(results), "evidenceDirectory": str(output)})


if __name__ == "__main__":
   logging.basicConfig(level=logging.INFO)
   main()
