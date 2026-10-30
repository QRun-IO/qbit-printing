# Follow-up

- [x] Versioned shared contract and HTTP happy-path test.
- [x] Durable enrollment, device-scoped authentication, inventory and printer approval.
- [x] PDF/ZPL queue, idempotency, content integrity and status events.
- [x] QQQ metadata and native enrollment/enable/test-print processes.
- [x] Durable enqueue EffectTarget for QQQ workflows.
- [x] Verify local laser output from connector and installed LocalSystem service; user confirmed both pages.
- [ ] Verify Zebra hardware when connected.
- [x] Add separate synthetic native QQQ frontend preview host.
- [x] Replace identifier text inputs with tenant-scoped, device-dependent selectors and add table/record actions.
- [ ] Qualify deployed QQQ host integration.
- [x] Separate local printer availability from server approval and block withdrawn queues.
- [x] Prepare public repository documentation, license, CI and GitHub prerelease artifacts.
- [x] Verify remote CI/security and publish v0.1.0-rc.1 JAR/POM/checksum assets.
- [x] Client-owned device/printer name, description and location synchronized into native QQQ metadata.
- [ ] Logical destinations and object-storage document backend.
- [ ] Run the coordinated Java gate for profile migration, long text and 10,000-row bounded-history regressions; qualify sustained throughput separately.
- [x] Device-scoped test/retry/cancel/queued-job routes and native record/table cancellation/reprint review, explicit duplicate-risk consent and retry idempotency.
- [ ] Server-side job expiry/document retention and operational alerts.
- [ ] PostgreSQL integration and multi-node stress tests; migration/version management.
- [ ] Resolve upstream/cached qbit-build-parent interpolation warning for Maven 3.10.
- [x] Add native Printing home with scoped operational widgets and actionable links.
- [ ] Complete dashboard-standard live attention, URL scope, stale/retry and responsive/accessibility acceptance.

## 0.1.1-rc.1 integration checkpoint

- [x] Verify current Java source: 19 tests passed, zero Checkstyle violations.
- [x] Preserve job outcome while retry creates a new job; reject cancellation after claim.
- [x] Expose cloud-queued jobs to the companion for visibility/cancellation before local receipt.
- [ ] Record the pending latest Windows changed-tree gate, including independent heartbeat during slow printing.
- [ ] Complete final MSI rebuild and actual elevated upgrade/UI acceptance after the user's pending UAC approval.
- [ ] Record final package hashes, signing status and applicable integration evidence before replacement release.
- [ ] Run a fresh explicitly authorized physical acceptance job for the final package; Zebra remains deferred.
- [ ] Qualify reboot/sign-in/service recovery and deployed-host behavior; no source-only or synthetic result substitutes for those checks.
