# Retained print acceptance (additive candidate)

This optional Java integration preserves the existing native generation print
process and the existing printing queue. It adds no device wire fields or REST
routes. It is disabled until the host supplies
`PrintingQBitConfig.withRetainedSourceAuthority`. Current `printing_operator`
(or administrator) authority and native tenant/device/printer record checks
remain mandatory. Submission also requires the shared `printingSubmit` process
permission. This grants no device management or generic job-history access.

## Public contract

`RetainedPrintFacade.Request(requestId, sourceLocator, printerRecordId, title,
optionsJson)` contains no actor or asserted source binding. Request IDs use
`[A-Za-z0-9._:-]{1,128}`, including owner namespaces such as `echo:<scanId>`.
`submit` derives the signed-in native actor and returns an immutable
`Acceptance`; it includes the exact source, job/event identities, tenant,
initiating actor, destination, title and normalized options JSON, including any
`mediaProfile` ID/revision. `find` authorizes the same complete request and
returns that acceptance, null for observed absence, or a conflict for an
unresolved upload reservation. Reuse the same request identity after a lost
response. Never convert an unavailable lookup into absence or create another
intent automatically.

The host-configured `RetainedPrintSourceAuthority.resolve(locator, requestId)`
must authorize the current native actor, source association and currently
eligible immutable version. It returns `SourceReference(owner, opaqueReference,
sha256, sizeBytes, mediaType)`. `opaqueReference` is at most 4000 Java
characters, stable and canonical under that owner's rules; printing does not
parse owner-specific fields. The optional Record Files adapter must encode its
complete version/source/generation-request/initiating-actor/tenant tuple and
compare generation's committed receipt. Accepted MIME types are
`application/pdf` and `application/vnd.zebra-zpl`; the current configured
PDF/ZPL limits apply before the byte read, within the existing integer-length
storage ceiling. `read(reference, requestId)` must enforce the same current
permissions and return only those exact bytes. Hash/length verification remains
in printing's existing submission path. Do not supply arbitrary source
authorities per request or reconstruct a user session.

Source admission remains the owner's policy. An explicitly configured published
template may qualify generated output without a per-label release dialog;
external-upload quarantine is unchanged. Printing performs no automatic release
and creates no human-release audit. `resolve` must be read-only and must not
invoke a generation reconciliation operation that can generate or dispatch work.

Source/destination checks surround owner callbacks and precede acceptance.
Submission refuses an ambient QQQ transaction: queue acceptance is independently
committed and cannot be rolled back by a caller's transaction. Submission also
enforces the native job-table credential and channel mutation ceilings; lookup
uses the corresponding read ceilings without granting generic table access.
`find` calls no byte reader, submission or event writer, and its option
normalization cannot initialize settings. It reauthorizes the source and
destination records, then compares the exact accepted intent; a later exposure,
media inventory or dispatch-limit change does not reinterpret that historical
intent. A later permission loss may deny returning a receipt whose job already
committed; it never authorizes a replacement job.

## Persistence and replay

Existing `printing_job` gains nullable `retained_actor_id`,
`retained_binding_json`, and `accepted_event_id`. Existing `printing_event`
gains nullable `event_origin`. `initialize` applies additive upgrades; old
jobs/events remain unbound/unclassified. None of these internal fields are added
to the device `JOB_COLUMNS` DTO or native table metadata.

Submission and exact lookup both use the existing tenant admission lock, so an
in-flight inline acceptance cannot be mistaken for absence. The unique
`(tenant_id, idempotency_key)` serialize submissions. Retained keys use
`retained:` plus the request ID. Ordinary/bound collisions refuse. Complete
normalized intent includes actor, owner reference, content hash/length/media,
destination, title, options and tenant. The existing storage reservation/orphan
path is reused; it may commit an upload reservation before writing object
storage. The final job, retained binding, and server acceptance event commit
together. Failed acceptance-event insertion rolls back the job. Rollback or
orphan-cleanup failures are suppressed onto the original failure rather than
replacing it. Existing initialization widens legacy 300-character titles to
1024; the bound title preserves exact whitespace and Unicode and is compared on
replay. Acceptance-event JSON has `type=ACCEPTED`, `origin=SERVER_ACCEPTANCE`,
`status=QUEUED`; it does not claim device receipt or physical output.

This is atomic acceptance binding inside printing, not a distributed transaction
over the retained owner's authorization database. Deployment still owns durable
database/storage settings, bounded provider calls and current session policy.

## Committed observations

`observation(requestId, eventId)` reads only an exact committed event joined to
the accepted job. New ordinary device events are stamped `DEVICE_OBSERVATION`;
pre-upgrade unclassified events and server acceptance events cannot be returned
as device evidence. The current native operator must have current device/printer
row authority and satisfy the job credential/channel read ceiling. Before
returning any source metadata, printing calls the configured owner's
`authorizeObservation(source, requestId)` under the current reporter session,
then rechecks the current ceiling and destination authority. Its default
implementation refuses; adapters must explicitly implement their current
source-observation policy. This callback does not grant content access or
impersonate the initiating actor. This is an operator-authorized observation
lookup, not device enrollment or permission to forge reporter identity. The
optional RF authority adapter must preserve initiating actor separately from
current reporting actor and map only supported stored states. Neither `SPOOLED`
nor `SPOOLER_COMPLETED` proves physical output.

## Verification scope

The tests use real H2 transactions and native QQQ row authorization with
synthetic printers. They never contact a device or emit physical output.
Coverage includes commit-then-throw acknowledgment loss, concurrent exact
replay, held-admission-lock lookup, mutation ceilings, owner observation
refusal, callback revocation, exact content hash/length, configured document
bounds, title upgrade/replay, read-only historical lookup and original-error
preservation when rollback fails. Qualification of this adopted source is
recorded in the session notes; the older peer candidate's test result is not
evidence for this revision. Cross-QBit integration remains a separate gate. No
release, install, shared-cache substitution or runtime migration has occurred.
