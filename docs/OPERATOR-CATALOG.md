# Operator catalog and queue media

This source adds native printing extension 2.2.0. Publication and Windows media
agent qualification are pending. Existing administrator and device routes keep
their permissions; this is an additive operator API, not a device bearer API.

Hosts grant `printing_operator` and the `printing_tenant` security key. The native
`printingSubmit` process remains the domain write permission. Current device and
printer table/record security, credential scopes and client-channel ceilings apply
on every operation. Generic job/command history, table writes, enrollment and
device management remain administrator-only. Hosts may add destination locks.
Native callbacks cannot replace the initiating actor, session, instance, service
or row-security context. Submission rechecks current process, credential, channel
and destination authority after loading/storage callbacks and before commit.
The backend process is hidden and has no base64-entry form. Echo obtains its user
selections from the catalog; backend metadata also references native printer and
format possible-value sources.

- `GET /qqq/v1/qbit/printing/operator/printers?limit=50&after=...` returns at most
  200 authorized approved/exposed printers, in stable printer-row-ID order. The
  opaque next cursor advances over at most `limit` candidate rows even if parent
  device security hides every candidate. Continue until `nextCursor` is null.
- `POST /qqq/v1/qbit/printing/operator/jobs` accepts `requestId`, `printerRowId`,
  `title`, `format` (`PDF` or `ZPL`), `contentBase64` and optional `options`. The
  native printer row determines the device and opaque Windows queue identity.
  `requestId` is 1–128 characters from letters, digits, `.`, `_`, `:`, `-`.
  The authenticated actor and tenant scope the stable key; callers cannot supply
  actor identity or an internal idempotency key. The response is the existing job
  receipt after durable commit. Changed requests conflict instead of reprinting.
- `GET /qqq/v1/qbit/printing/operator/requests/{requestId}` returns only that
  actor's exact accepted request, or 404. It does not enqueue, read document bytes,
  or initialize settings. Current destination read authority remains required;
  later loss of exposure alone does not erase the acceptance receipt.
  Lookup checks the original accepted native printer-row ID. Deleting that row
  and creating another with the same device/queue pair does not transfer history.

Each catalog entry has `printerRowId`, `deviceId`, `printerId`, effective `name`,
`deviceName`, `status`, `formats`, `mediaProfiles`, and optional `description` and
`location`. These names are presentation; the stable row identity selects a queue.

Media profiles report actual Windows queue defaults. Their typed facts are `id`,
`revision` (lowercase SHA-256), `name`, `widthMicrons`, `heightMicrons`, `dpiX`,
`dpiY`, `source: WINDOWS_QUEUE_DEFAULT`, `applyMode: DRIVER_DEFAULT`. Unknown or
unsupported driver facts produce an empty list. The initial client reports one
configured default; the wire bound is 32. Dimensions are 1–2,000,000 microns and
DPI is 1–9600. Names are at most 512 characters. Unknown additive reported facts
are ignored when canonicalizing; known required facts remain validated. Arbitrary
page-size values are never interpreted as a Windows stock change.
Profile and selection IDs share the protocol's 128-character URL-safe ID bound.

Select exact reported media with `options.mediaProfile: {id, revision}`. The
selection is immutable job intent and accepts no extra fields. New submissions
reject stale/missing profiles before admission/storage writes; exact accepted
replays remain accepted. The capable Windows agent rechecks its actual current
default before spooling. Selected-media dispatch requires the current agent
capability `printer-media-v1`, including after reconnect/downgrade. Legacy
inventory without profiles clears stale media facts. Omitted selection preserves
ordinary existing queue behavior. Paper-width/height overrides remain unsupported,
including when a media selection is supplied.

Generated/retained source authorization belongs to the configured source owner.
The retained facade shares this operator permission and destination boundary; it
preserves its atomic accepted source/actor/request binding. Barcode fit and
encoding remain the renderer's job.

`QUEUED` is durable acceptance; `SPOOLED` means spooler acceptance. Neither is
physical output confirmation. These tests use synthetic bytes and no real printer.
