# Operations and troubleshooting

## Configuration

| Setting | Applies to | Behavior |
| --- | --- | --- |
| `QQQ_PRINT_ADMIN_TOKEN` | Protocol demo | Required random secret of at least 24 characters, supplied through process environment |
| `QQQ_PRINT_PORT` | Protocol demo | Defaults to 18081; binds loopback only |
| `QQQ_PRINT_JDBC_URL` | Protocol demo | Defaults to `jdbc:h2:file:./.local/printing` |
| `printing.ui.port` JVM property | Synthetic UI preview | Defaults to 18082; binds loopback only |
| `printing.protocol.fixtures` Maven property | Contract tests | Defaults to sibling `../qqq-print-protocol/fixtures` |

An integrated QQQ host supplies its own DataSource/pool, backend metadata, tenant identity and management authorization. Remote connectors require an HTTPS endpoint. Keep credentials out of command-line arguments, source files and diagnostic captures.

## Printer selection and approval

The Windows companion controls which queues are exposed. Inventory is a complete snapshot of that selection, not a delta. The server retains omitted queues for history but sets `available=0`. Administrator approval (`enabled`) is independent. Both are required for new job submission and claims. Re-exposing a previously approved queue restores availability without granting new approval to other queues.

Queue hardware status comes from the connector's driver observation. An online connector does not guarantee paper, toner or label availability. A disconnected connector leaves the last observation stale. The service identity must be able to enumerate and print to the queue; a mapping installed only for an interactive Windows user may not be visible to LocalSystem.

## Jobs

Normal progression is QUEUED → CLAIMED → RECEIVED → SUBMITTING → SPOOLED. SPOOLED records Windows acceptance. The application must not interpret it as proof of physical delivery.

Use a stable business idempotency key for retries of the same request. A changed target/document/options with the same key is a conflict. A deliberately new print requires a new key. The initial implementation does not automatically reclaim RECEIVED/SUBMITTING jobs or resend ambiguous output; inspect the queue and physical result before deciding to print again.

The pending 0.1.4 implementation stores new documents through the host's configured dedicated QQQ S3 storage table; existing inline database content remains readable. Administration defaults to 365 days after server-observed successful completion, with bounded scheduled cleanup when a host scheduler is configured. Active, failed, uncertain and unknown-completion legacy jobs remain protected. See [storage and retention](STORAGE-AND-RETENTION.md) for bucket separation, recovery and versioning details. No live storage migration or cleanup is implied by a source upgrade.

## Admission capacity and inventory history

The host configures resource ceilings through `PrintingQBitConfig.withAdmissionLimits(PrintingAdmissionLimits)`. The producer passes these limits to every submission path, including native processes, direct Java submissions, connector test prints and explicit retries. Retention remains a separate administrator setting, defaulting to 365 days after successful completion.

| Limit | Per device | Per tenant |
| --- | ---: | ---: |
| Outstanding jobs and upload reservations | 10,000 | 100,000 |
| Outstanding decoded document bytes | 10 GiB | 100 GiB |
| Retained decoded document bytes, including terminal jobs and upload reservations | 2 TiB | 20 TiB |
| New admissions in a rolling hour | 2,000 | 20,000 |
| Cumulative printer identities | 1,000 | 20,000 |

`PrintingAdmissionLimits.defaults()` returns these defaults. To override them, construct the record with device/tenant pairs in this order: outstanding jobs, outstanding bytes, retained bytes, hourly admissions, cumulative printers. Values must be positive. Use the same configuration on every QQQ node serving a tenant. Size retained capacity for actual document sizes and the chosen retention period; 10,000 jobs/day is a sizing target, not a measured hardware throughput result. These are logical application byte limits, not an S3 billing limit: object versions, multipart fragments and provider overhead require their own deployment monitoring and lifecycle policy.

Admission is serialized per tenant in SQL before storage writes. Pending and failed upload reservations stay charged until exact-object cleanup succeeds; failed admissions that reached a committed reservation also consume the hourly allowance. Legacy reservations with unknown length conservatively reserve the maximum 20 MiB each, against the tenant when their device is unknown. Existing payloads are backfilled without removal. Apply this additive accounting migration with older application writers stopped; mixed-version writers or direct SQL changes bypass accounting and are unsupported.

HTTP 429 means capacity, the rolling-hour allowance, cumulative inventory history, or an existing upload reservation prevents a new allocation. The response explains the category. Reuse the same idempotency/request identity when retrying: a completed matching replay returns its existing job even at capacity or after the original retry source expires. Wait for outstanding work or the hourly window, investigate failed cleanup/storage access, or have the host administrator adjust a justified capacity limit. Retention only removes eligible successful documents; it does not erase failed, uncertain or active work to make space.

Inventory snapshots remain limited to 200 entries. Rotating IDs cannot bypass cumulative history limits, and rejected snapshots leave approval, availability and job references intact. Existing IDs remain updateable when a legacy database already exceeds a limit. Management `GET /printing/v1/admin/devices/{deviceId}/printers` accepts `limit` (1–200, default 200) and opaque `before` from the preceding response's `nextCursor`. The response is `{ "printers": [...], "nextCursor": null | "..." }`, ordered by stored name and row identity. The existing Java `printers()` method returns the first bounded array; use `printersPage()` for additional history. Native QQQ tables retain their own backend paging.

## Device controls and durable action receipts

The native client API is the registered `printing` extension, semantic version
`1.0.0`, under `/qqq/v1/qbit/printing`. Discover it through QQQ's version endpoint;
its generated OpenAPI fragment is `src/main/resources/openapi/qqq-v1/printing.yaml`.
The contract test checks this fragment against the registered endpoint specs and
the initial semantic compatibility baseline. Core QQQ session authentication and
credential scopes run before printing operations; the application does not issue
another kind of user login token.

Every native operation requires the authenticated QQQ principal, `printing_admin`,
the configured tenant security key, and applicable native table, process and row
access. Device operations additionally require `X-QQQ-Device-Id` and
`X-QQQ-Device-Token` as secondary proof. Enrollment at `/devices/enroll` validates
the user's facility access before consuming the enrollment code and atomically
binds the device to the core user's stable identity. A legacy unbound device can
be bound only through explicit `/device/bind` with valid proof and native device
access. Ordinary requests never bind or transfer ownership implicitly.

`GET /session` returns the current principal and tenant; valid bound device headers
add the device identity. Native administration can manage another user's device
when current permissions allow it, without impersonating that user. Queue clear
and inventory updates authorize affected existing rows before mutation and compare
the reviewed identity set under the device lock. Claims authorize the exact job
and printer before dispatch, ordered by creation time then identity. An inaccessible
printer fails closed rather than granting access through its parent device.

Queue-clear review is bounded to 10,000 jobs. It performs a native Get for each
job and each distinct printer, with the device checked once. Get-specific policies
remain effective. Large clears on remote databases have not been performance
qualified; this review cost is separate from normal claim throughput.

Developer projections that request derived `device_status`, `control_status`, or
`command_status` must also select `id`. The normal native UI includes it. An
identity-free projection is rejected rather than returning a fabricated UNKNOWN
status; the frozen QQQ dependency has no pre-query projection hook for these fields.

Printing selection processes also enforce native table access before extraction
and preview counts. The printing extract wrapper preserves the core filter,
callbacks, and tenant row locks, then requires permission enforcement on the
query. Later review/load checks still reauthorize the selected records.

Authorization uses the native QQQ context established for each request. Host
authentication and session refresh policies determine when permission changes
reach that context; printing does not assert that an upstream cached session is
fresh. Cookie-authenticated unsafe calls must also satisfy the host's Origin/CSRF
policy. The local host requires its canonical same-origin authority.

Device controls use the same tenant-scoped service facade from native management
and transport adapters. Adapters must authorize the current user, tenant, device,
and action before calling the facade; a device ID or an actor string is not an
authorization grant. The legacy `/printing/v1` device bearer transport remains a
compatibility interface, not a substitute for native QQQ user permissions.

Pause blocks new claims. Disconnect also expresses that ordinary agent heartbeat,
inventory publication, and claiming should stop; the independent authenticated
control poll remains available for reconnect. Neither operation cancels in-flight
jobs. Resume preserves connection state; reconnect preserves pause. Revocation
invalidates the enrolled credential permanently, blocks control polling, and
requires separate local re-enrollment. It cannot be undone with resume/reconnect.
Desired state, the last observed revision, and the last poll are separate facts:
a successful server action does not prove an offline device applied it.

Queue clear atomically changes only this device's QUEUED jobs to FAILED with the
existing cancellation reason. It preserves documents, accounting, and history;
CLAIMED, RECEIVED, SUBMITTING, and uncertain work remain untouched. Claim and clear
share the device lock across server instances. Spool cancellation accepts only an
owned job with a recorded spool identity. The agent must independently verify its
local connection-scoped ownership before asking the operating system to cancel;
the server never accepts an arbitrary queue name, spool ID, or executable.

Every management action supplies a new `requestId` and a UTC `issuedAt`, retained
unchanged across retries. A retained matching request returns its existing record;
a changed body returns 409. Without an existing record, requests older than 15
minutes return 410, and times over 60 seconds in the future return 400. Review a
new action explicitly when a form expires. Never silently refresh its time or
reuse its identity. Terminal records become eligible for bounded cleanup after
seven days; an unchanged old request still expires after its receipt is removed.

Remote inventory refresh and owned spool cancellation require advertised agent
capabilities, expire after 15 minutes, and have a limit of 100 pending commands
per device. Polls deliver at most 20 commands. The retained action history is
limited to 1,000 records per device, with one additional reserved revocation;
eligible old terminal records are reclaimed before admission. Capacity returns
429 explicitly. History pages contain at most 200 records and use the preceding
`nextCursor`; an expired or foreign cursor cannot disclose another device.

Agents durably journal remote command identity before any effect and return the
same receipt on duplicate delivery. Expiry stops delivery but does not prove that
an earlier attempt did not execute: a first late receipt may replace provisional
EXPIRED with its actual outcome. Once stored, a receipt is immutable; conflicting
replays return 409. `CANCEL_REQUESTED` means the operating system accepted a
cancellation request, not that paper was withdrawn or a physical print was undone.

## Common failures

| Symptom | Check |
| --- | --- |
| Enrollment rejected | Code expiration/single use, correct server, administrator-created facility/device enrollment |
| No printers in QQQ | Service access to Windows queues; local exposure selection; connector enrollment and inventory publication |
| Printer listed but submission rejected | Both `available` and `enabled`, format capability and 20 MiB decoded-size limit |
| Job stays queued | Connector connectivity, selected queue availability and device assignment |
| Job stays received/submitting after restart | Inspect local journal/spooler; automatic uncertain-output replay is intentionally absent |
| Printing app missing or access denied | QBit registration, `printing_admin`, matching `printing_tenant`, and correct QQQ backend |
| Contract test cannot find JSON fixture | Sibling checkout at the pinned protocol commit, or `printing.protocol.fixtures` override |
| Maven model warning/failure | Use wrapper 3.9.11; the upstream parent model has a known interpolation warning and the local Maven 3.10 rejected it |
| UI demo lacks frontend assets | Build with `-Pui-demo` and use its generated runtime classpath; protocol demo has no frontend |

## Release qualification

Prior released checks include user-confirmed laser PDF and Zebra ZPL/PDF output with correct machine driver settings. Audit source checks cover H2, native processes, host HTTP registration, synthetic storage and small real connection pools. Follow-up qualification includes live S3/IAM, PostgreSQL, production host authentication, upgrade/migration paths, multiple QQQ nodes, prolonged disconnects and a broader printer/driver set. Current prerelease artifacts must not be described as certifying those scenarios.
