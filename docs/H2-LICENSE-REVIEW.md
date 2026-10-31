# H2 license review — 2026-10-30

Status: evidence and proposed correction only. No license exception is enabled by this document.

The dependency-review job for `fb99faf` reported `com.h2database:h2@2.4.240` as `LicenseRef-scancode-unknown AND MPL-2.0`. Its vulnerability check found no advisories at the configured low-or-higher threshold. The package upgrade fixes the confirmed embedded database compaction defect; reverting the driver would restore that defect.

## Published terms and artifact

The upstream [2.4.240 license](https://github.com/h2database/h2database/blob/version-2.4.240/LICENSE.txt) expressly offers a choice of MPL-2.0 or EPL-1.0. Its [license FAQ](https://github.com/h2database/h2database/blob/version-2.4.240/h2/src/docsrc/html/license.html) describes unmodified binary redistribution and attribution. The published Maven POMs for [2.4.240](https://repo.maven.apache.org/maven2/com/h2database/h2/2.4.240/h2-2.4.240.pom) and [2.3.232](https://repo.maven.apache.org/maven2/com/h2database/h2/2.3.232/h2-2.3.232.pom) both declare MPL 2.0 and EPL 1.0. The upgrade does not introduce a new license choice.

The local host uses the unmodified Maven Central H2 JAR under the MPL-2.0 option. The reviewed 2.4.240 binary SHA-256 is `29b70e427cc1c40cdc376283adbb0cc62853073797bb5fe5761f81fe73d57ce0`. Preserve H2 attribution, license information and access to the corresponding [source tag](https://github.com/h2database/h2database/tree/version-2.4.240) when distributing a host runtime. The QBit's own Apache-2.0 license does not replace third-party terms.

## Applicable policy and scope

`K22-DEP-02` in the [dependency standard](https://github.com/KofTwentyTwo/standards/blob/main/standards/dependencies.md) permits MPL-2.0 when used unmodified. However, the pinned reusable [`pr.yml`](https://github.com/KofTwentyTwo/standards/blob/fdf5ed029603c938615a32b1beca3628b8787945/.github/workflows/pr.yml) requires every `allow-dependencies-licenses` entry to have a record in `exceptions/register.md`. The [exception policy](https://github.com/KofTwentyTwo/standards/blob/main/policies/exceptions.md) requires that record to pass normal review. No existing record was found covering this repository and H2.

The substantive license is permitted; correcting the scanner result still needs the recorded workflow exception. This review does not authorize changing shared policy, skipping a required check or marking the license gate successful without that correction.

## Narrow proposed correction

After the applicable exception is recorded, use the reusable workflow's package-specific `allow-dependencies-licenses` input for `pkg:maven/com.h2database/h2`. Preserve `license-check: true`, `vulnerability-check: true`, the low advisory threshold and every other package's existing license policy. Do not globally allow unknown licenses or broaden the MPL allowlist.

The pinned action's [license matcher](https://github.com/actions/dependency-review-action/blob/a1d282b36b6f3519aa1f3fc636f609c47dddb294/src/licenses.ts) calls [`purlsMatch`](https://github.com/actions/dependency-review-action/blob/a1d282b36b6f3519aa1f3fc636f609c47dddb294/src/purl.ts), which intentionally ignores package versions. Adding `@2.4.240` to the input does **not** enforce a version restriction. The required security inventory gate now rejects absent H2, any version other than the reviewed `2.4.240`, and multiple H2 versions. It compares the selected Maven graph with both scanner inventories. A later upgrade must update both the pin and this reviewed-version guard after a renewed license assessment. Cite the approved exception ID beside the workflow input and give the record a review/expiry date.

This document leaves that configuration unapplied pending the required record. The shared standards repository was not modified.
