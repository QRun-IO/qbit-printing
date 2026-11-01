# H2 license review — 2026-10-30

Status: [EX-0013](https://github.com/KofTwentyTwo/standards/blob/7d758b13449057e442d658a089a3c55aa3a6d171/exceptions/register.md#ex-0013) was approved in [standards PR #54](https://github.com/KofTwentyTwo/standards/pull/54) on 2026-10-31. The caller enables only the recorded H2 package license-metadata exception; fresh hosted gates remain required.

The dependency-review job for `fb99faf` reported `com.h2database:h2@2.4.240` as `LicenseRef-scancode-unknown AND MPL-2.0`. Its vulnerability check found no advisories at the configured low-or-higher threshold. The package upgrade fixes the confirmed embedded database compaction defect; reverting the driver would restore that defect.

## Published terms and artifact

The upstream [2.4.240 license](https://github.com/h2database/h2database/blob/version-2.4.240/LICENSE.txt) expressly offers a choice of MPL-2.0 or EPL-1.0. Its [license FAQ](https://github.com/h2database/h2database/blob/version-2.4.240/h2/src/docsrc/html/license.html) describes unmodified binary redistribution and attribution. The published Maven POMs for [2.4.240](https://repo.maven.apache.org/maven2/com/h2database/h2/2.4.240/h2-2.4.240.pom) and [2.3.232](https://repo.maven.apache.org/maven2/com/h2database/h2/2.3.232/h2-2.3.232.pom) both declare MPL 2.0 and EPL 1.0. The upgrade does not introduce a new license choice.

The local host uses the unmodified Maven Central H2 JAR under the MPL-2.0 option. The reviewed 2.4.240 binary SHA-256 is `29b70e427cc1c40cdc376283adbb0cc62853073797bb5fe5761f81fe73d57ce0`. Preserve H2 attribution, license information and access to the corresponding [source tag](https://github.com/h2database/h2database/tree/version-2.4.240) when distributing a host runtime. The QBit's own Apache-2.0 license does not replace third-party terms. Standard Maven resources include the repository LICENSE and NOTICE under `META-INF` in the QBit JAR; the existing font license/provenance assets remain included. The earlier d1821be artifact omitted the root notices, so its source attribution alone did not demonstrate packaged attribution.

## Applicable policy and scope

`K22-DEP-02` in the [dependency standard](https://github.com/KofTwentyTwo/standards/blob/main/standards/dependencies.md) permits MPL-2.0 when used unmodified. The pinned reusable [`pr.yml`](https://github.com/KofTwentyTwo/standards/blob/fdf5ed029603c938615a32b1beca3628b8787945/.github/workflows/pr.yml) requires every `allow-dependencies-licenses` entry to have a record in `exceptions/register.md`. EX-0013 passed the [exception policy](https://github.com/KofTwentyTwo/standards/blob/main/policies/exceptions.md)'s normal gates and was merged at `7d758b13449057e442d658a089a3c55aa3a6d171`.

EX-0013 covers only this repository and the unmodified H2 2.4.240 binary identified above. It expires on 2027-04-30 or an H2 version/license change or correction of GitHub's classification, whichever occurs first. It does not permit changing shared license policy, skipping vulnerability review or bypassing a required check.

## Package-specific correction

The caller uses only the reusable workflow's package-specific `allow-dependencies-licenses` input for `pkg:maven/com.h2database/h2`. It preserves `license-check: true`, `vulnerability-check: true`, the low advisory threshold and every other package's existing license policy. Unknown licenses are not globally allowed, and the MPL allowlist is not broadened.

The pinned action's [license matcher](https://github.com/actions/dependency-review-action/blob/a1d282b36b6f3519aa1f3fc636f609c47dddb294/src/licenses.ts) calls [`purlsMatch`](https://github.com/actions/dependency-review-action/blob/a1d282b36b6f3519aa1f3fc636f609c47dddb294/src/purl.ts), which intentionally ignores package versions. Adding `@2.4.240` to the input does **not** enforce a version restriction. The required security inventory gate now rejects absent H2, any version other than the reviewed `2.4.240`, and multiple H2 versions. It compares the selected Maven graph with both scanner inventories. A later upgrade must update both the pin and this reviewed-version guard after a renewed license assessment. Cite the approved exception ID beside the workflow input and give the record a review/expiry date.

The workflow cites EX-0013 beside the input. Any renewal or change requires normal review of the central record and this caller; the package exclusion must not outlive the record's scope or expiry. This correction does not approve a release or live server cutover.
