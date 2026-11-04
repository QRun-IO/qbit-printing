# Contributing

Use Java 21 and the checked-in Maven 3.9.11 wrapper. Clone `QRun-IO/qqq-print-protocol` as a sibling and check out the protocol commit pinned in `.gitea/workflows/qrun-release.yaml`. See README for the Windows and Unix build commands.

Run `bash ./mvnw -Pui-demo verify` (`.\mvnw.cmd -Pui-demo verify` on Windows), matching the primary CI profile. The validate phase enforces the repository Checkstyle configuration; tests cover real H2 persistence, native QQQ metadata/processes and the public loopback HTTP contract. Add focused coverage for changed behavior. Do not replace spooler acceptance with a physical-print assertion.

Changes belong in a pull request on [the primary Gitea repository](https://git.kof22.com/QRun-IO/qbit-printing) with a concise behavior description and actual validation results. Update README, changelog, relevant tests and shared contract together when changing the wire interface. Use Conventional Commit subjects. Do not include local databases, logs, device credentials, customer documents or machine-specific printer information.

Keep the QBit compatible with QQQ 4.1+ and resolve runtime services through the current QQQ instance. Preserve tenant/device isolation and the distinction between local exposure (`available`) and server approval (`enabled`). Application callers must authorize destinations before submitting or emitting an effect.

Maintainers publish reviewed versions through the primary Gitea CI and trusted Maven publisher, then attach the verified JAR/POM/checksums to a Gitea release. Follow [release operations](docs/RELEASES.md). GitHub is a shadow/archive; its Actions are retired for this repository. Historical `.github` scripts remain available as local quality checks. Deployment and physical printer acceptance are separate from library publication.

## Script quality

Owned `.github/scripts/*.py` follow the KofTwentyTwo standard at revision `fbfc8d2cf3b5e16ae9750b04cf9de1794f1e7152` (config adapted from its `python` profile): three-space indentation, Ruff 0.17.3 format/lint (locked in `uv.lock`) and strict Pyright 1.1.414 (Microsoft's npm package, a development-only dependency locked in `package-lock.json` and run through Node 24). Restore with `uv sync --locked` and `npm ci --ignore-scripts`, then run:

```text
uv run --locked --no-sync python .github/scripts/test-script-quality.py
uv run --locked --no-sync python .github/scripts/check-script-quality.py
```

`check-script-quality.py` fails for any tracked Python outside `.github/scripts/` or any JavaScript/TypeScript (this repository has no tsconfig), and requires Pyright's analyzed-file count to equal the selected scripts. Pyright must run as `pyright -p .github`: it silently skips dot directories from a root config. Documented exceptions: CLI `print` only in `check-*.py`; synthetic test assertions and boundary constants; narrow inline `noqa` comments for fixed no-shell Git/interpreter calls and ordered fail-closed validators. Do not loosen a rule or add a type waiver to pass.
