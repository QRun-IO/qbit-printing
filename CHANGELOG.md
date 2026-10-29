# Changelog

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
