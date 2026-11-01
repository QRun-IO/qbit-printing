# Session state — 2026-10-31

## Local Docker development — 2026-11-01

The developer tooling is published on `main` through [PR #21](https://github.com/QRun-IO/qbit-printing/pull/21),
merged as `3592decba8087439aff5d1b75a9726c72f460cb9`; its tree matches tested
head `def8d961f3d82a1235f8e45ffbeacb0fbe79c421`. The final hosted
[development run](https://github.com/QRun-IO/qbit-printing/actions/runs/41324370247)
passed the actual 40-job HTTP/WS smoke and 65 S3 checks, nine pinned-Core discovery
compatibility cases, and all 33 script tests on each of Windows and Ubuntu.
The [Java run](https://github.com/QRun-IO/qbit-printing/actions/runs/41324370253)
reported 133 tests: 132 executed, one intentionally skipped opt-in workload test,
zero failures/errors, and zero Checkstyle violations. That workload separately
passed on the combined source. These are development-tooling publication results,
not a server release or live runtime upgrade. The experimental API 1.9 candidate
remains opt-in and is not adopted into the released application dependencies.

The current qualification scope is local Docker only. The new `dev/Invoke-Dev.ps1`
provides owned Start/Test/Stop commands for a native QQQ TABLE host, dedicated
MinIO storage and optional real Authentik/PostgreSQL. Source images and the client
Core archive are pinned. Generated credentials stay in private state outside the
checkout; no installed connector, live database or physical printer is used.

Portable Docker checks passed: the default 40-job smoke, 65 actual QQQ S3 backend
checks, and an opt-in 10,000-job/two-tenant/eight-client run with 30,000 durable
server receipts and 400 submission plus 400 receipt replays. The burst completed
in 147.856 seconds (about 67.63 jobs/second); its enqueue-to-ACK p95 was 32.984
seconds while work intentionally queued. This is HTTP/WS with a fake file printer
and a simple fixture client journal, not TLS, hardware or a long soak claim.

Native TABLE login, identity, frontend asset and logout passed. Real Authentik
configuration/discovery passed; a registered redirect continued to login and the
otherwise-identical unregistered redirect was rejected. The released RC.20 host
still advertises API 1.8 without the native session grant: browser login, renewal
and the Windows callback are not established by these checks. All owned test
containers, volumes and networks were removed. Lifecycle/bootstrap regressions
passed 28 cases including experimental-profile refusal and diagnostic-redaction
checks; scripts passed parser and PSScriptAnalyzer checks.

The separate opt-in native-auth profile passed eight actual checks against the
immutable upstream API 1.9 candidate `ecf0f550a0357cec6387c2b54efca128a405a629`:
ordinary TLS refusal before container-only CA installation, typed-grant discovery,
real Authentik login through the production Core PKCE client, wrong-Origin cookie
rejection, denied-user permissions, logout replay refusal, server-side renewal
after access-token expiry and denial after provider refresh-token revocation.
The final run exited zero and all owned containers, volumes and networks were
removed. Earlier failed fixture attempts remain in private evidence; their fixes
were standard-flow readiness/binding, bounded provider redirects and the correct
Authentik CSRF header. No authentication check was disabled.

This experimental candidate is not the default released RC.20 runtime or a
published production dependency upgrade. Container HTTPS and automated real
provider flows do not qualify the Windows browser callback UI, host trust,
months-long operation or physical printing. See [local development](../dev/README.md),
[provider limits](../dev/AUTHENTIK.md) and [native qualification](../dev/native-auth/README.md).
Earlier release checkpoints below are historical. This developer-tooling merge
did not publish a new server product release or change the live paired runtime.

## Opt-in service workload regression — 2026-11-01

The combined test/development change also includes a 10,000-job, two-tenant,
eight-lane file-backed service regression with replay, exact accounting,
pagination and cold reopen checks. It is opt-in and never accesses a printer.
The original isolated full gate passed 119 tests with zero Checkstyle violations;
the final exact receipt-count assertion passed its focused rerun. Its source is
unchanged when integrated here, and the combined checkout passed one focused
workload test with zero Checkstyle violations. The earlier released-artifact
network run and
40-job idle latency measurements are recorded in
[qualification evidence](QUALIFICATION-2026-11-01.md), separately from the portable
Docker run above. No runtime source, dependencies or version changed.

## Active device controls and standard QQQ authentication

Implementation is in the isolated device-controls worktree. Native device/table actions support pause, resume, disconnect, reconnect, revoke, inventory refresh and queued-job clearing; job records support exact owned Windows-job cancellation. Related printers, jobs, pending queue and bounded command history are registered. Derived status separates connection presence, desired state and observed revision. Domain writes use current QQQ permissions; clearing a queue checks every affected job before the transactional mutation.

The primary client transport is the discovered `/qqq/v1/qbit/printing` semantic extension with standard QQQ user sessions and secondary device proof. Authentik requires the shared advertised native PKCE session grant; no private bearer-only fallback substitutes for that authorization. The final isolated Java 21 gate passed **110 tests**, zero failures/errors/skips and zero Checkstyle violations. All 204 runtime dependencies match the frozen approved hashes. Native HTTP, proof/binding denials, explicit permission enforcement, selection count/extraction, FIFO eligibility, queue races, cancellation replay and offline command expiry are covered. Bound device tokens are rejected on legacy routes, and new legacy enrollment defaults off; real HTTP regressions prove there is no secondary-proof downgrade. The actual .NET client passed 23 native table-based authentication/binding/control/logout checks without printers or jobs. Current protected CI and installed/provider acceptance remain pending. No new feature release or local installation has occurred.

The user confirmed all three current Query test prints good. Barcode catalog expansion was removed from printing scope and belongs to document/barcode generation.

## Query mascot correction

Built-in diagnostic pages and labels now use the official Query dog artwork from [qrun.io/query](https://qrun.io/query), with the exact original asset and provenance recorded alongside the resource. White padding preserves its aspect ratio within the existing layout; warehouse glyphs, media profiles, barcode and print routing are unchanged. All five PDF profiles and three thermal resolutions are covered by the new regression. The isolated full build passed **73 tests with no failures/errors/skips and zero Checkstyle violations**. Generated PDF previews and actual ZPL graphic-field reconstruction were reviewed. The tested resource is packaged and the previous logo is absent.

This server-only correction is published in [v0.1.4-rc.1](https://github.com/QRun-IO/qbit-printing/releases/tag/v0.1.4-rc.1), and local native deployment is accepted. The Windows client already requests these server-generated documents; no client icon change is needed. Installed-client acceptance passed with credentials and history preserved. Physical output from the new Query diagnostics remains unconfirmed.

## Published audit release 0.1.4-rc.1

Local paired-host acceptance is complete following verified recovery backups. Native authentication, bounded record queries, anonymous/cross-origin/authentication-table denials, logout and the enabled **365-day** retention policy passed. Pairing and history were preserved, and the connector reconnected automatically. This acceptance used database document storage and did not request a new print. Dedicated production S3 provisioning, IAM and remote deployment remain unqualified.

All required checks passed for reviewed Query commit `c34883d`; protected merge `412ffbbc` preserves that exact tree. [Release run 41190650664](https://github.com/QRun-IO/qbit-printing/actions/runs/41190650664) passed every build, security and CodeQL gate before publishing assets for signed tag `v0.1.4-rc.1`. The current gate records **73 passing tests and zero Checkstyle violations**. All 204 runtime dependency coordinates are unchanged from the qualified audit runtime. The public JAR, POM and SHA256SUMS passed checksum and GitHub attestation verification, constrained to the exact source commit, tag and release workflow. All 48 JAR entry contents, including 38 implementation classes, match the reviewed Query CI package. Release JAR SHA256: `b31aa58588e2bad6b1ca63e5904906c0379ef75b6d2b550ad1680e668312065b`.

The preceding S3 safeguard checkpoint passed **72 Java tests, zero failures/errors/skips and zero Checkstyle violations**, with independent source review complete. It binds documents to the reviewed physical key layout and native separator using unambiguous ordered JSON. Historical reads, new retries and cleanup reject incompatible bindings before object IO; inline database content and prior retry receipts remain compatible. Old experimental S3 bindings are not silently adopted.

Actual isolated Windows-to-Linux qualification passed on local candidate JAR SHA256 `9163b5fb89c981b70d2bd416ca3920a17bcbda2fe61eaa7190f050cc27759495` with all 204 runtime libraries verified on both hosts. Read and retry returned 409; cleanup recorded an error with zero removals. A positive-control-checked native SDK observer recorded zero transmissions for those refusals. Content availability, tenant/device retained bytes, admission/orphan/job counts and the exact original object stayed unchanged. The earlier candidate reproduced false expiration of a Windows-key object from Linux; this correction prevents that loss of availability. Portable upstream keys, explicit historical migration and production IAM remain separate work. Fresh hosted checks for this correction and the scoped license input now pass.

Packaged candidate `00dd2dd` passed hosted [build](https://github.com/QRun-IO/qbit-printing/actions/runs/41162272066), [CodeQL](https://github.com/QRun-IO/qbit-printing/actions/runs/41162272168) and [security](https://github.com/QRun-IO/qbit-printing/actions/runs/41162272058). The isolated frontend-profile gate passes **67 Java tests, zero failures/errors/skips and zero Checkstyle violations**. All **204** runtime dependencies match the selected Maven graph and scanner inventories, with exactly one H2 2.4.240 JAR. Refreshed Trivy reports zero HIGH/CRITICAL findings; OSV reports zero malicious findings. The exact CI package includes matching LICENSE/NOTICE resources; all 38 implementation classes and existing runtime assets match the earlier `fb99faf` candidate.

The exact `fb99faf` CI application JAR and verified runtime payload passed isolated native acceptance against a fresh private copy of a closed database: real frontend, native authentication, bounded table counts/queries, denied requests, logout and retention settings at the **365-day default**. The native scheduler actually ran at `2026-10-31T02:45:00.087433600Z` and removed zero documents. After identity-verified forced termination, an independent SELECT-only read-only H2 inspection **before application initialization** found all 11 tables, including the four audit tables, with retention and record counts preserved. Restarting the same exact artifact passed the native checks again at `2026-10-31T02:47:10Z`. This qualifies the tested forced-termination/cold-reopen path. The graceful-shutdown check is recorded below. No live paired database, remote host, S3 storage or printer was changed by these checks.

Graceful shutdown is now separately verified on the exact packaged `00dd2dd` CI artifact. A fresh isolated closed-data copy passed native acceptance. A minimal private Java Attach agent invoked `System.exit(0)` after the owned process identity was checked; the real registered launcher hook stopped Quartz and HTTP, and the supervisor recorded JVM exit 0 at `2026-10-31T03:09:26.8635088Z`. Read-only JDBC inspection before reinitialization confirmed all required tables, retention and counts. The separate Attach client timed out waiting for a reply after server exit and was cleaned up; the server itself exited normally. All private children stopped and no physical output was requested. This does not qualify Windows reboot, service-manager shutdown or unattended supervision.

The prepared-copy failure was the upstream H2 2.3.232 close-time compaction defect (#4247/#4249). Replacing only H2 with **2.4.240** preserves the migrated schema using normal compaction. No warm-up, retry, delay or disabled-compaction production workaround is present. The public fresh-file cold-reopen test checks durability; it does not claim to reproduce every affected old-driver database. See [local runtime and backup guidance](LOCAL-HOST.md).

The earlier H2 license-metadata gate is resolved. [Standards PR #54](https://github.com/KofTwentyTwo/standards/pull/54) merged EX-0013 through normal required checks following explicit user approval. Its separately approved CodeQL repair retains Actions and JavaScript/TypeScript under the advanced security-extended workflow. Printing applies only the approved package input; its fresh [PR gates](https://github.com/QRun-IO/qbit-printing/actions/runs/41185576692) pass. The inventory guard still requires exactly unmodified H2 2.4.240, with 17 passing guard tests; all other license, vulnerability and branch protections remain enforced. See [license evidence and expiry](H2-LICENSE-REVIEW.md).

The audit also fixes native-process replay, tenant-scoped facility selectors, duplicate-output risk preservation, atomic queue/retained-byte/rate admission and bounded printer history. Dedicated QQQ document storage and scheduled retention protect active, failed and uncertain work. Stop older writers before schema/accounting migration and preserve verified recovery copies. See [audit evidence](AUDIT-2026-10-30.md), [operations](OPERATIONS.md) and [storage](STORAGE-AND-RETENTION.md). Real PostgreSQL/S3, remote authentication, multi-node operation and sustained physical throughput remain unqualified. Local acceptance, shared-standards approval and tagged audit publication are complete.

Record Files' **21:54 America/Chicago checkpoint on 2026-10-30** is acknowledged. Its latest typed reader and configured reporting authority are candidate source with focused tests; the verified installed artifact is still pending, and the earlier snapshot is not the final contract. The teams agree on the consumer-owned observation boundary and are resolving identifier bounds and optional spooler-ID canonicalization. Printing still needs its durable accepted source/initiator/job/destination binding, tenant-scoped committed event receipt and legitimate current native reporting identity before an adapter can be enabled. No adapter is enabled, no browser session or fabricated authority is retained, and spool completion is not physical-output confirmation. Standalone byte submission remains independent.

## Branded diagnostic documents — v0.1.3-rc.1 verified and published

Built-in test paths now share PrintingTestDocuments and TestPrintProfile. Native QQQ forms and device test requests choose Letter, A4 or 4 × 6-inch label profiles at 203/300/600 dpi; omitted profiles retain PDF Letter/ZPL 203 defaults. ZPL rejects page profiles. Documents use the QQQ logo and wordmark, bundled OFL JetBrains Mono Bold, character-pair checks and a 25.4 mm reference. PDF embeds vector text; ZPL carries a monochrome bitmap in whole-row graphic strips of at most 99,999 bytes each and native Code 128. No persistent font download is required. Identical PDF generation uses stable document IDs for idempotency; changed request content still conflicts.

Source `a6266fe` passed the full `mvnw.cmd verify` gate: **28 tests and zero Checkstyle violations**. This includes generator/media/font/margin tests, per-strip byte/row/hex bounds and full bitmap reconstruction checks at every density, plus native/API parity and profile validation. Exact-source [build 41139885188](https://github.com/QRun-IO/qbit-printing/actions/runs/41139885188), [CodeQL 41139885415](https://github.com/QRun-IO/qbit-printing/actions/runs/41139885415) and [security 41139884980](https://github.com/QRun-IO/qbit-printing/actions/runs/41139884980) passed. The [v0.1.3-rc.1 release](https://github.com/QRun-IO/qbit-printing/releases/tag/v0.1.3-rc.1) JAR, POM and SHA256SUMS assets are public and verified.

PrintingTestDocumentsExport writes local PDF/PNG/ZPL previews without contacting printers. Installed Windows 0.1.7 passed all 16 UI checks, and its published release assets are verified. The loopback QQQ server was restarted with the new code using the same paired database and retained credentials. Exactly one Letter PDF to the laser, one LABEL_4X6_300 ZPL to the Zebra and one LABEL_4X6_300 PDF to the Zebra initially reached SPOOLED through the installed management pipe, device API and shared generator. Their bytes matched the reviewed previews. The user confirmed the laser page and the Zebra ZPL label, but the initial Zebra PDF did not appear.

The Zebra machine driver defaults were 4.09 × 2 inches and Thermal Transfer, while the loaded stock required 4 × 6 inches and Thermal Direct. Windows Printing Defaults were corrected; the paused printer was restored to Thermal Direct and resumed. One fresh submission of the same generated PDF through QQQ administration and the installed service advanced the hardware counter by one while preserving Thermal Direct, and the user confirmed correct physical output. All three built-in format paths are now physically confirmed after the configuration correction; no application code fix was required. Barcode scanning and measured alignment remain unverified. Deployed remote QQQ integration and sustained throughput remain separate qualification work.

## Historical source and integration checkpoints

2026-10-30 source0.1.2-rc.1: implemented client-authoritative Name/Description/Location synchronization, preserved computer/queue technical names, read-only native QQQ profile fields and friendly selector labels. Added additive schema backfill/widening, dispatch/history indexes, one-row payload-free claims and metadata-only keyset history pages. Added long-text, explicit-clear, migration, tenant and synthetic 10,000-row pagination/claim tests. Root verified22tests with zeroCheckstyle violations using mvnw.cmd verify. Protocol28definitions/21fixtures passed and CI pins a0de93b. Exact-source GitHub build41126545760 and security41126545795 passed for cfe8a5f. Prior verification below predates these changes. No physical prints were requested by this work.

Current source is prepared for **0.1.1-rc.1**. Latest Java verification passed **19 tests with zero Checkstyle violations**. The full audit follow-up implements device-scoped test-print/retry/cancel/queued-job routes and native **Print again** / **Cancel queued jobs** actions from job records and table selections. Native review uses named records, rechecks administrator/tenant/row access and current state, requires explicit duplicate-risk confirmation for uncertain/spooled output, and uses a stable request identity for retry replay. Retry creates a new job and preserves the original outcome. Queued cancellation is serialized against claim and cannot promise cancellation after dispatch.

The Windows companion now displays cloud-queued jobs before receipt, allowing server-validated cancellation while still queued. It also has independent heartbeat during slow native printing, bounded subprocess isolation/orphan cleanup, persistent history/review and retained deduplication evidence. The final Windows changed-tree gate passed **102 tests (36 Core + 66 Windows/CLI), zero build warnings/errors**, including the heartbeat and queued-cancellation regressions.

Final Windows MSI rebuild is in progress. Actual elevated upgrade/interactive acceptance awaits the user's UAC approval. No fresh physical print has occurred for these latest changes; prior confirmed laser pages apply to their tested builds. Zebra, final reboot/task/recovery qualification and publisher signing remain pending. Root owns gate results, package/install evidence, commits and release; do not claim 0.1.1-rc.1 is published or installed yet.

## Historical publication and implementation evidence

Published: [v0.1.0-rc.1](https://github.com/QRun-IO/qbit-printing/releases/tag/v0.1.0-rc.1) at f7f451a. Tagged build/test/Checkstyle, secrets, workflow, dependency and malicious-package checks all passed before publication. JAR, POM and SHA256SUMS assets are public. Guava is fixed at 33.7.2-jre. The security workflow resolves Maven dependencies first, verifies the scanned graph and feeds the resulting CycloneDX inventory to OSV; this avoids Maven throttling and the upstream parent-POM placeholder parsing issue without skipping checks. Main protection requires verify and all three security jobs. Release JAR SHA-256: fefce42496a534f3227f496a0e5f9a6f8e9209e7175ddf14b18166968a6ad4d6.

Live frontend RC.37 acceptance: dashboard links and the six operational printer columns were inspected; selected-printer Print a test label review/submit completed successfully against synthetic data; record actions were verified. Safe screenshots and instructions are in UI-WALKTHROUGH.md. No additional physical print was sent by this UI demo. At that earlier acceptance checkpoint, the local preview ran at `http://127.0.0.1:18084` (override of default 18082); inspect current processes before assuming it is still running.

2026-10-29: Initial QQQ 4.1 printing QBit and durable protocol server prepared for 0.1.0-rc.1 publication. QQQ baseline 4.1.0-RC.20. Server scope includes JDBC storage, scoped device credentials, inventory approval, queue submission, replay-safe events, metadata, native enrollment/enable/test-print processes and durable enqueue target. Root owns commits/publication; this checkpoint does not assert remote publication.

Build: `mvnw.cmd verify` (offline only after dependencies are cached). Tests cover native metadata/processes, persistence/idempotency/isolation/status rules, local printer withdrawal and the real HTTP fixture round trip. Checkstyle is copied unchanged from qbit-jobs. Machine Maven 3.10 cannot consume the locally cached parent POM; use wrapper 3.9.11. Runtime classpath is generated at target/runtime-classpath.txt.

Hardware integration is owned by the root agent under cloud-printing-integration. No service-side change sends a print without an explicit submitted job. Local protocol demo listens on 127.0.0.1:18081 by default and requires QQQ_PRINT_ADMIN_TOKEN from environment.

Both the development connector and installed LocalSystem service printed laser test pages confirmed by the user. Temporary MSI install/start/uninstall passed. Zebra hardware remains deferred. The synthetic native QQQ UI demo is a separate loopback host on port 18082 and registers no device transport routes.

Printer availability now tracks the full locally selected exposure snapshot separately from server approval. Omission sets available=0; new submissions and claims require available=1 and enabled=1. Protocol CI fixture pin: f977bd01b62ceb7539190fd2861809537fcf504c.

Publication files include Apache-2.0 license/notice, README integration and operations guidance, contribution/security policies, issue/PR templates, changelog and SHA-pinned GitHub verify/tag-release workflow. Artifacts are GitHub JAR/POM/checksums, not Maven Central publication. Runtime .local/log/target data is ignored.

PrintingHome adds permission-guarded native widgets with tenant-scoped QueryAction/CountAction reads, native record/filter links, explicit scope/threshold/freshness text and guided setup. Dashboard standard gaps are recorded in README. PrintingHomeTest exercises real H2 reads, tenant isolation, unprivileged/unscoped rejection and native links; fixture backend names are unique because QQQ ConnectionManager caches providers by backend name.

Final QBit UI pass: metadata uses printing_* table/data-source names, printing-prefixed processes/widgets, named dependent device/printer selectors, explicit enum choices and operational default table columns. Printer table and record actions use native streamed ETL review/result screens; multi-selection queues one job per selected printer. Native management actions require the configured service tenant in the current session printing_tenant key, including enrollment. Fourteen tests and Checkstyle passed with `mvnw.cmd -o verify` after these changes. Root owns final demo restart/browser acceptance and publication.

Durable target correction: core DurableJobsRuntime.effectLoop constructs a system session without user tenant keys. PrintEnqueueEffectTarget now has a private trusted resolver for that system-only outbox path, bound to the current instance PrintingQBitConfig service and its fixed tenant. Inline users retain scope checks; native process authorization is unchanged. Registered-target tests cover system deliver/reconcile, idempotent replay, foreign-tenant device rejection, cached target reuse across instances, and inline wrong/missing-tenant denial.
`mvnw.cmd -o verify -Pui-demo dependency:build-classpath` passed all 16 tests and zero Checkstyle violations after the durable target correction, explicit disabled table writes, final dashboard timestamps, and frontend RC.37 profile update. UI runtime classpath regenerated at target/ui-runtime-classpath.txt. Primary identifiers remain non-hidden because QQQ excludes hidden fields from ordinary query results; explicit frontend column hints keep them out of the default operational grid.

## Real-time transport implementation resumed — 2026-10-31

User resumed implementation after explicitly rejecting periodic job/control polling. Active isolated worktrees: qbit-printing-wt-realtime, qqq-print-protocol-wt-realtime, qqq-print-agent-wt-job-table (all branch feat/realtime-printing-20261031). Installed 0.2.0-rc.1 remains unchanged and is still polling; do not claim it is real-time.

Protocol extension 1.1.0: client-initiated authenticated WSS, qqq-printing.v1 subprotocol, current QQQ session and secondary bound-device proof. Small state/commands/events/receipts travel directly over WebSocket; separate HTTPS documents stream to protected files. No timed polling fallback. Commit notifications and finite event-triggered claims, bounded concurrency, persistent receipts and reconnect reconciliation are being implemented. Tenant administrator forms expose document, transfer, connection, queue/rate/printer/storage limits. Default PDF limit100MiB, ZPL20MiB; both configurable. Root owns native forms/admission settings, server agent owns route/storage/service, Windows agent owns transport/pipeline, inventory agent completed protocol70definitions/72fixtures and now owns Jobs UI expansion/sorting. No live install or new physical print yet. New-source gates/integration are in progress; previous0.2 gates do not qualify these changes.

## Real-time source verification — 2026-10-31

Server source0.3.0-rc.1 passes117 Java tests and zero Checkstyle violations, including real native-session WebSocket lifecycle, sessionId/sessionUUID, logout/channel rejection, replacement fencing, partial revision acknowledgments, no idle-inventory feedback, and21MiB native storage streaming/integrity. Protocol70definitions/72fixtures passes; both consumer workflows pin signed protocol67be986cb76d6b5d9a9f72cad054193b0d11b0d5. Native admin policy tests cover permission/tenant checks, exact250.5MiB conversion, revision persistence and invalid-capacity rejection. A private actual.NET/Java fixture passed13 functional checks with25MiB content, durable receipt replay, channel replacement and sign-out denial; its initial fixture JVM needed explicit maintenance-thread shutdown handling, so this is not installed lifecycle qualification. Root is preparing the final refreshed fixture run. Current installed0.2 remains unchanged; release/install acceptance is pending.

## Final real-time API gate — 2026-10-31

Native printing extension is now2.0.0, because requiring a synchronized WebSocket for previously HTTP-only native claims is a breaking behavior change. Framework/qqq/v1, wirev1 and qqq-printing.v1 remain unchanged; product0.3.0-rc.1. Full final-source verify passed118tests, zero failures/errors/skips and zero Checkstyle violations. Configured heartbeat timeout now determines dashboard freshness (90s legacy floor) and authorized PING records control-only contact. Published1.0 OpenAPI baseline remains preserved. Protocol repin and final Windows verification are pending; no live deployment.

## Coordinated protocol and cross-runtime verification — 2026-10-31

Current immutable consumer pin is df3729ac632d4a34a3fb8423acef465042a624fc (71definitions/73fixtures); it supersedes earlier67be986 pre-major-correction checkpoints. Final API2.0 Java/Release.NET interoperability passed13 checks with25MiB content and exact durable replay, temporary host stopped and wrapper exit0. Installed0.2 remains unchanged. Windows source gate398tests passed before one final startup recovery regression was added; its definitive gate is still pending. No release/install acceptance claim.
