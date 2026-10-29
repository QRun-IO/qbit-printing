# Contributing

Use Java 21 and the checked-in Maven 3.9.11 wrapper. Clone `QRun-IO/qqq-print-protocol` as a sibling and check out the protocol commit pinned in `.github/workflows/ci.yml`. See README for the Windows and Unix build commands.

Run `./mvnw verify` (`.\mvnw.cmd verify` on Windows). The validate phase enforces the repository Checkstyle configuration; tests cover real H2 persistence, native QQQ metadata/processes and the public loopback HTTP contract. Add focused coverage for changed behavior. Do not replace spooler acceptance with a physical-print assertion.

Changes belong in a pull request with a concise behavior description and actual validation results. Update README, changelog, relevant tests and shared contract together when changing the wire interface. Use Conventional Commit subjects. Do not include local databases, logs, device credentials, customer documents or machine-specific printer information.

Keep the QBit compatible with QQQ 4.1+ and resolve runtime services through the current QQQ instance. Preserve tenant/device isolation and the distinction between local exposure (`available`) and server approval (`enabled`). Application callers must authorize destinations before submitting or emitting an effect.

Maintainers create reviewed version tags. CI produces JAR/POM/checksum assets and publishes GitHub prereleases for hyphenated versions. Maven Central publication, deployment and physical printer tests are separate operations; this workflow does not perform them.
