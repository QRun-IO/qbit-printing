# Changelog

## 0.3.0-rc.1 — 2026-10-31

Upgrade the server before the matching Windows connector. Native printing API
2.0 requires synchronized authenticated WSS; old native clients cannot claim
without it, and there is no polling fallback. Wire payloads remain v1.

- Add client-initiated realtime WebSocket delivery under native printing API 2.0,
  with current QQQ session and record permissions revalidated during connection use.
- Deliver desired state, device commands, inventory, lifecycle events and receipts
  over the socket; native claims require acknowledged current settings and state.
- Stream retained documents independently from controls and expose document,
  concurrency, timing, admission and retention batch limits in native admin forms.
- Preserve durable job/command identities across reconnects. Installed Windows
  realtime and physical-output acceptance remain separate from source/test gates.

## 0.2.0-rc.1 — 2026-10-31

- Add native device/table selection actions for pause, resume, disconnect,
  reconnect, revocation, inventory refresh and queued-job clearing, plus exact
  owned Windows-job cancellation and bounded command history.
- Show device presence, desired versus applied control state, related printers,
  jobs and queue; include connection/control issues in the printing dashboard.
- Register semantic printing extension `1.0.0` at `/qqq/v1/qbit/printing`, using
  standard QQQ user sessions and secondary device proof with current native
  record/process permissions. Legacy bearer transport remains explicit compatibility.
- Preserve journal/history and distinguish accepted commands from confirmed
  execution. Shared-core/provider native authentication acceptance and installed
  candidate qualification remain required before deployment claims.
- Explicitly enforce current native permissions during selection counts and
  extraction, including callbacks and query selections. Pass 110 Java tests and
  25 real .NET client/native QQQ interoperability checks without print jobs,
  including rejection of legacy enrollment and bound-token downgrade attempts.
- Reject account-bound device tokens on legacy routes and disable legacy
  enrollment by default. Legacy-only hosts opt in explicitly; existing unbound
  devices retain migration compatibility.

## 0.1.4-rc.1 — 2026-10-31

- Use the official Query dog mascot on every built-in PDF and ZPL diagnostic profile, preserving the original artwork and aspect ratio. Full local and hosted gates pass 73 tests with zero Checkstyle violations; PDF previews and reconstructed thermal graphics pass visual review. Local native deployment is accepted; physical output from the updated Query diagnostics remains unconfirmed.
- Add an authenticated loopback QQQ host that shares durable paired devices/jobs with native screens and retention scheduling, preserving the separate synthetic UI demo.
- Make native enrollment and test-print execution replay-safe and retain known duplicate-output risk after later failures.
- Strictly validate print options, inventory formats/status and event bounds before persistence; recheck current queue format support at claim time.
- Add dedicated S3 document storage through the host's QQQ storage API, tenant object namespaces and immutable destination/reference checks.
- Bind S3 documents to the reviewed native key layout and platform separator using unambiguous ordered identity components, including multiline base paths; reject incompatible or old experimental bindings before historical object access and preserve cleanup accounting. Validate new retry sources before reserving uploads while retaining replay and inline database compatibility. Portable upstream keys and historical-object migration remain separate work.
- Add admin retention settings with a 365-day default, reviewed cleanup and bounded scheduled maintenance; preserve job history and uncertain work.
- Use named facility choices during enrollment and revalidate them under the current user's tenant/record permissions.
- Require release security/CodeQL gates, verified artifact promotion and build provenance; report the real packaged QBit version.
- Enforce configurable atomic queue/storage/rate budgets and cumulative printer limits, with bounded management pagination and stable replay at capacity.
- Consume bounded claim/cancel request bodies before mutation so a persistent HTTP connection remains usable for the subsequent status event.
- Pin H2 2.4.240 to fix close-time compaction data loss; verify file-backed schema/settings across cold reopens and document closed-backup upgrade qualification.
- Verify the exact frontend-inclusive runtime inventory in Maven, Trivy and OSV; fail on malformed or incomplete scanner evidence.
- Include repository LICENSE and NOTICE under META-INF using standard Maven resources; preserve font license, provenance and diagnostic assets.
- The S3 safeguard checkpoint passed 72 tests with zero failures/errors/skips and Checkstyle violations, including incompatible S3 bindings, multiline prefix retargeting, retry accounting and inline document compatibility. Actual cross-platform refusal preserves objects and accounting with zero SDK requests. Required build/security/CodeQL/dependency gates pass with the approved scoped H2 record. Qualified artifacts passed isolated graceful/cold-restart checks and local paired-host acceptance with native authentication, preserved records, automatic connector reconnection and 365-day retention. No live S3 migration or remote deployment is claimed.
- Publish the JAR, POM and SHA256SUMS after [release run 41190650664](https://github.com/QRun-IO/qbit-printing/actions/runs/41190650664) passed all gates. Verify checksums and GitHub attestations for all three assets against the release tag and source; all packaged entry contents match the reviewed Query CI artifact.

## 0.1.3-rc.1 — 2026-10-30

- Shared QQQ-branded warehouse test pages and labels across native record/table actions and device requests.
- Bundle licensed JetBrains Mono Bold; embed PDF glyphs and use bounded ZPL graphics for consistent identifier readability.
- Add Letter/A4 and 4 × 6 profile options with explicit 203/300/600-dpi label resolution, compatible defaults and validation.
- Add font/media, deterministic generation, ZPL bitmap reconstruction and operator action regression coverage.

## 0.1.2-rc.1 — 2026-10-30

- Synchronize client-managed device/printer display names, descriptions and locations; preserve physical computer/queue names and stable identifiers.
- Add read-only native profile fields, longer names and additive schema migration/backfill.
- Bound queue claims and history reads, avoid loading document payloads for metadata, and provide keyset-paged admin history.
- Verify 10,000 synthetic jobs, long-text profiles, migration, explicit clearing and tenant isolation (22 tests, zero Checkstyle violations).

## 0.1.1-rc.1 — 2026-10-29

- Device-scoped test print, explicit reprint and pre-dispatch cancellation routes.
- New-job reprints with stable action idempotency and duplicate-risk consent for spooled/uncertain originals.
- Native job table and record actions with review screens, readable selections, tenant/permission checks and state revalidation.
- Nineteen tests and zero Checkstyle violations, including authenticated HTTP operator actions and native multi-selection workflows.

## 0.1.0-rc.1 — 2026-10-29

Initial prerelease for QQQ 4.1+, verified against QQQ 4.1.0-RC.20.

- Durable JDBC enrollment, device credentials and print queue.
- Printer inventory with separate local exposure (`available`) and administrator approval (`enabled`); withdrawn queues cannot receive new claims.
- PDF/ZPL submission, business idempotency, authenticated claims/content and replay-safe status events.
- Namespaced QQQ Devices/Printers/Jobs metadata with friendly relationships, status choices and operational default columns.
- Tenant-scoped dependent printer selectors, native table/record actions with review/result screens, and guarded enrollment.
- Scoped Printing home dashboard and a durable enqueue effect target.
- Legacy local `print_*` data migrates to `printing_*`; job-to-printer relationships are populated without changing the wire contract.
- Loopback protocol server and separate synthetic native QQQ frontend demo.
- Sixteen automated tests, Checkstyle gate and pinned shared protocol fixtures.
- GitHub CI plus JAR/POM/checksum prerelease artifacts.

The development connector and installed LocalSystem service each printed a laser test page confirmed by the user. Zebra hardware, production signing/deployment, richer destination management and recovery workflows remain follow-up work.
