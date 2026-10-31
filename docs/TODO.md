# Follow-up

- [x] Correct S3 layout and multiline-prefix binding ambiguity; full isolated gate passes 72 tests with zero Checkstyle violations and independent review complete.
- [x] Reproduce the actual Windows-to-Linux false-expiration defect, then qualify the corrected runtime: zero SDK requests for refused reads/retries/cleanup and preserved objects, availability and accounting.
- [ ] Require fresh hosted gates for the S3 correction; portable upstream keys, historical migration and production IAM remain separate qualification work.
- [x] Verify the H2 2.4.240 candidate at `fb99faf`: 67 Java tests, zero Checkstyle violations, hosted build/CodeQL/security pass, and all 204 selected runtime dependencies verified.
- [x] Qualify the exact CI artifact on an isolated closed-data copy: native frontend/authentication/query/denial/logout/retention checks, actual scheduled zero-removal tick, forced termination, read-only inspection before initialization, and successful cold restart; all 11 tables and retained state preserved.
- [x] Add the exact reviewed H2 version guard and license attribution/evidence; 17 Python guard tests and workflow lint pass.
- [x] Approve central H2 metadata record EX-0013 through standards PR #54 and apply only its package-scoped workflow input; fresh complete hosted gates remain required before release or cutover.
- [x] Qualify registered JVM-hook shutdown on exact `00dd2dd`: native acceptance, Quartz/HTTP shutdown, exit 0 and independent read-only cold inspection before initialization pass. Windows reboot/service-manager behavior remains separate.
- [ ] Perform authorized live paired-host cutover from a closed backup after all gates pass; preserve pairing and verify the real native records and connector heartbeat.

- [x] Finish audit regressions for native-process replay, strict options/inventory validation, retained duplicate risk and event field bounds; full gate passes 53 tests.
- [x] Implement admin-configurable document retention (one-year default), bounded scheduled cleanup and existing QQQ object-storage integration, retaining history/receipts and protecting active or uncertain output.
- [ ] Implement the optional Record Files adapter against its verified frozen contract: durable accepted-source/actor binding, legitimate current reporting identity and committed observation receipt. Adapter remains disabled; standalone printing does not depend on it.
- [ ] Verify tagged audit release gates, published provenance and deployed-host qualification.
- [x] Historical audit PR #10 checkpoint aeac1a6 passed hosted checks; current H2/native qualification and remaining license gate are recorded above.
- [x] Complete the independent baseline scan and current-source reviews of all five findings and remediations.
- [x] Add authenticated native QQQ local host sharing paired data with device routes and the existing scheduler; 65 tests/zero Checkstyle pass and independent source review found no blocker.
- [ ] Complete live authenticated-host deployment after the isolated native acceptance above; keep remote/S3 qualification separate.

- [x] Versioned shared contract and HTTP happy-path test.
- [x] Durable enrollment, device-scoped authentication, inventory and printer approval.
- [x] PDF/ZPL queue, idempotency, content integrity and status events.
- [x] QQQ metadata and native enrollment/enable/test-print processes.
- [x] Durable enqueue EffectTarget for QQQ workflows.
- [x] Verify local laser output from connector and installed LocalSystem service; user confirmed both pages.
- [x] Confirm physical built-in ZPL and PDF output on the connected Zebra; scanning and measured alignment remain separate checks below.
- [x] Add separate synthetic native QQQ frontend preview host.
- [x] Replace identifier text inputs with tenant-scoped, device-dependent selectors and add table/record actions.
- [ ] Qualify deployed QQQ host integration.
- [x] Separate local printer availability from server approval and block withdrawn queues.
- [x] Prepare public repository documentation, license, CI and GitHub prerelease artifacts.
- [x] Verify remote CI/security and publish v0.1.0-rc.1 JAR/POM/checksum assets.
- [x] Client-owned device/printer name, description and location synchronized into native QQQ metadata.
- [x] Implement the dedicated QQQ object-storage document backend; live S3/IAM qualification remains pending configuration.
- [ ] Logical destination mappings.
- [x] Run the coordinated Java gate for profile migration, long text and 10,000-row bounded-history regressions; included in the passing 28-test v0.1.3-rc.1 gate.
- [ ] Qualify sustained throughput separately from synthetic bounded-history tests.
- [x] Device-scoped test/retry/cancel/queued-job routes and native record/table cancellation/reprint review, explicit duplicate-risk consent and retry idempotency.
- [x] Implement admin document retention and bounded scheduled cleanup, default 365 days.
- [ ] Server-side job expiry and operational alerts.
- [ ] PostgreSQL integration and multi-node stress tests; migration/version management.
- [ ] Resolve upstream/cached qbit-build-parent interpolation warning for Maven 3.10.
- [x] Add native Printing home with scoped operational widgets and actionable links.
- [ ] Complete dashboard-standard live attention, URL scope, stale/retry and responsive/accessibility acceptance.

## Historical 0.1.1-rc.1 integration checkpoint

- [x] Verify current Java source: 19 tests passed, zero Checkstyle violations.
- [x] Preserve job outcome while retry creates a new job; reject cancellation after claim.
- [x] Expose cloud-queued jobs to the companion for visibility/cancellation before local receipt.

The Windows gate, MSI rebuild, installed UI acceptance, release evidence and fresh hardware submissions pending at this historical checkpoint are superseded by the newer records below and the Windows acceptance notes. Zebra is connected and both label format paths are physically confirmed.

- [ ] Qualify reboot/sign-in/service recovery and deployed-host behavior; no source-only or synthetic result substitutes for those checks.

## v0.1.3-rc.1 diagnostic documents

- [x] Share branded diagnostic generators and explicit stock/resolution profiles across native QQQ and device test-print entrypoints.
- [x] Verify source a6266fe: full Maven gate passed 28 tests with zero Checkstyle violations; exact-source build, CodeQL and security workflows passed.
- [x] Publish v0.1.3-rc.1 and verify its public JAR, POM and SHA256SUMS assets.
- [x] Installed Windows 0.1.7 passed all 16 UI checks; reviewed built-in preview bytes matched the three submitted documents.
- [x] Submit one Letter PDF to the laser, one 4 × 6-inch 300 dpi ZPL and one matching PDF to the Zebra through the installed pipe/device API/shared generator; all reached SPOOLED with the original paired database retained.
- [x] User confirmed the Letter laser page and the Zebra label containing the barcode, identifying the latter as the built-in ZPL output.
- [x] Correct the Zebra machine driver defaults from 4.09 × 2-inch Thermal Transfer to 4 × 6-inch Thermal Direct, restore/resume the printer, and confirm one fresh built-in PDF printed correctly. No application code fix was needed.
- [x] Verify published Windows 0.1.7 assets; all three built-in physical paths are confirmed: laser PDF, Zebra ZPL and Zebra PDF.
- [ ] Obtain explicit barcode scan and measured alignment confirmation; successful spooling is not physical acceptance.

- [x] Complete independent review after the five baseline security findings are remediated; current details are in SESSION-STATE.md.
- [ ] Complete deployed-host and real S3 qualification; source tests are not deployment evidence.
