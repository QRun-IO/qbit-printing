# QQQ Printing

Cloud printing for QQQ 4.1+, paired with the [Windows AppKit connector](https://github.com/QRun-IO/qqq-print-agent) and the [shared protocol](https://github.com/QRun-IO/qqq-print-protocol). The Windows connector opens an outbound, authenticated two-way WebSocket; documents download separately over HTTPS. The cloud needs no inbound route into the facility. Release candidate **0.3.0-rc.1** requires native printing API **2.0**, with no job/control polling fallback. Upgrade the server before the matching Windows connector.

**Prerelease software**, tested against QQQ **4.1.0-RC.20** and Java 21. Current release candidate: [**0.3.0-rc.1**](https://github.com/QRun-IO/qbit-printing/releases/tag/v0.3.0-rc.1). The previous [**0.2.0-rc.1**](https://github.com/QRun-IO/qbit-printing/releases/tag/v0.2.0-rc.1) tagged build passed 110 tests and zero Checkstyle violations. The optional native UI demo uses QQQ frontend **1.0.0-RC.37**. GitHub releases provide a library JAR, POM and SHA-256 checksums, with build attestations for all three assets. Verify the exact downloaded release; publication does not establish installed or physical-output acceptance. This repository does not claim Maven Central publication or provide a standalone executable/fat JAR.

Implemented: single-use enrollment, device credentials, inventory/approval, PDF/ZPL submission, durable JDBC queue, idempotency, claim/content/events, native QQQ setup/test-print processes, read-only device/printer/job tables and a durable enqueue effect target.

The 0.2.0-rc.1 source adds native record/selection actions, per-device printers/jobs/queue/history, desired versus observed status and standard-session authentication at the discovered `/qqq/v1/qbit/printing` extension. Device credentials become secondary identity proof alongside the current QQQ user's permissions. Upgrade the server before the 0.2.0 Windows client, then explicitly associate an existing unbound pairing during sign-in. These changes are included in the published release above. See the [authentication contract](https://github.com/QRun-IO/qqq-print-protocol/blob/main/docs/QQQ-AUTHENTICATION.md); bound devices cannot fall back to legacy `/printing/v1` authentication.

```mermaid
flowchart LR
  A[QQQ application / durable job] --> B[Printing QBit and JDBC queue]
  C[Windows service] <-->|Client-initiated WSS: commands and status| B
  C -->|Separate outbound HTTPS document streams| B
  C --> D[Windows spooler]
  D --> E[Local laser / Zebra]
```

See the [real-time host integration](docs/REALTIME.md), [screen walkthrough](docs/UI-WALKTHROUGH.md), [storage and retention](docs/STORAGE-AND-RETENTION.md), [operations and troubleshooting](docs/OPERATIONS.md), [contributing](CONTRIBUTING.md), [security reporting](SECURITY.md), [changelog](CHANGELOG.md), and [Apache-2.0 license](LICENSE).

## Local development

Start an isolated native QQQ application, MinIO and optional Authentik with the
[Docker development commands](dev/README.md). The default test runs synthetic
Java/.NET WebSocket jobs and S3 checks without an installed connector or printer.

## Build and verify

Install Java 21. Clone this repository and `qqq-print-protocol` as siblings. This source pins protocol commit `f3cbc02e9b0fb4fdfefaa59d976c04df747cc589`; use that commit for reproducible compatibility tests. Use the checked-in wrapper (Maven 3.9.11).

```powershell
.\mvnw.cmd verify
.\mvnw.cmd dependency:build-classpath '-Dmdep.outputFile=target/runtime-classpath.txt' '-DincludeScope=runtime'
```

On Linux/macOS use `bash ./mvnw verify` and the corresponding wrapper commands. First builds download dependencies; `-o` is only appropriate after they are cached.

Checkstyle runs during validate. Tests exercise real H2 persistence, native QQQ metadata/processes and real loopback HTTP through Javalin, including the shared fixtures and withdrawal of a locally exposed printer. Keep `../qqq-print-protocol` checked out or pass `-Dprinting.protocol.fixtures=<fixture-directory>`.

## Legacy protocol demo

The new Windows client needs the [authenticated local application](#authenticated-local-application) below, or a QQQ application hosting the native printing extension. This protocol-only demo is for explicitly enabled legacy diagnostics; it does not provide native user sign-in.

Set `QQQ_PRINT_ADMIN_TOKEN` to a random value of at least 24 characters in the process environment; never put it in source or command arguments. Then:

```powershell
.\mvnw.cmd exec:java
```

The demo binds `127.0.0.1:18081`, persists H2 at `.local/printing`, and exposes `/printing/v1`. Optional `QQQ_PRINT_PORT` and `QQQ_PRINT_JDBC_URL` override those defaults. It does not log the administrative token. Local HTTP is for development; terminate TLS for remote connectors.

For a directly managed Java process, classpath is `target/classes;` followed by `target/runtime-classpath.txt`, and main class is `com.kingsrook.qbits.printing.PrintingDemo`.

## Native QQQ UI preview

A separate local demo serves the real QQQ frontend with synthetic device/printer/job records. It binds only to `127.0.0.1:18082` and uses mock authentication. Keep it isolated from real pairing data and physical connectors; it is a screen preview, not an authenticated deployment.

```powershell
.\mvnw.cmd -Pui-demo package dependency:build-classpath '-Dmdep.outputFile=target/ui-classpath.txt' '-DincludeScope=runtime'
$uiClasspath = 'target/classes;' + (Get-Content target/ui-classpath.txt -Raw).Trim()
java -cp $uiClasspath com.kingsrook.qbits.printing.PrintingUiDemo
```

Open `http://127.0.0.1:18082`. On Linux/macOS use `:` as the classpath separator. Set JVM property `-Dprinting.ui.port=<port>` before the main class to choose another port. This preview is distinct from the credential-protected protocol demo on port 18081; neither replaces deployment-specific authentication and authorization.

## Authenticated local application

`PrintingLocalHost` combines the real QQQ dashboard, native processes, authenticated sign-in and device routes against one durable H2 database. It binds only to loopback, uses QQQ table-based authentication, and starts the native Quartz retention schedule through `QApplicationLauncher`. Existing pairing and job history are retained when the database and tenant are preserved. Use the pinned H2 2.4.240 runtime; older 2.3.232 file-backed deployments require a closed backup and cold-reopen qualification. See [local host setup](docs/LOCAL-HOST.md) for explicit credentials, the required frontend/runtime dependencies and a reviewed cutover procedure.

The protocol-only `PrintingDemo` and synthetic, mock-authenticated `PrintingUiDemo` remain separate. Do not point the synthetic demo at real paired printer data. The local host retains database document storage until an actual dedicated S3 binding is configured in a deployment-owned application; it does not claim a remote cloud deployment.

Local native deployment is accepted with authentication and access-denial checks, preserved pairing/history, connector reconnection and 365-day retention. The previously accepted 0.1.4-rc.1 release passed 73 tests and zero Checkstyle violations; the user confirmed all three Query diagnostic outputs good. Production S3/IAM and remote deployment remain separate qualification work. The 0.2.0 controls/authentication release passed 25 real client/native QQQ interoperability checks; the user later confirmed installed Letter PDF and Zebra ZPL output, the Connected footer and both printers. These results do not qualify installed or physical behavior for 0.3.

## Add to a QQQ application

For a source checkout, `mvnw.cmd install` (or `bash ./mvnw install`) installs the verified library into your local Maven repository. For a GitHub release, download the JAR, matching POM and `SHA256SUMS`, verify the checksums, then install those artifacts locally or through your organization's artifact repository. Example using the release files in the current directory:

```powershell
.\mvnw.cmd install:install-file '-Dfile=qbit-printing-0.3.0-rc.1.jar' '-DpomFile=qbit-printing-0.3.0-rc.1.pom'
```

After the artifact is available in your configured repository, add:

```xml
<dependency>
   <groupId>com.kingsrook.qbits</groupId>
   <artifactId>qbit-printing</artifactId>
   <version>0.3.0-rc.1</version>
</dependency>
```

Supply an existing JDBC DataSource/pool and a QQQ RDBMS backend pointing to that same database. Initial schema is created by `initialize()`; the implementation uses portable H2/PostgreSQL SQL, but only H2 has been tested so far.

Device and printer names, descriptions and locations are edited in the Windows client and synchronized through heartbeat/inventory. QQQ's native record forms and selectors show the effective friendly name; the physical computer/queue name remains visible in Details. These synchronized fields are read-only in QQQ so two editors do not overwrite each other. Approval remains a separate server control. Friendly names/locations allow 512 characters, descriptions 4096, and physical names/job titles 1024. Clearing a friendly name restores the technical name without changing IDs or routing.

`initialize()` adds profile columns, backfills physical names, widens existing name/title columns and creates dispatch/history indexes without deleting records. The existing `name` column becomes the effective display label; `computer_name`/`queue_name` retain technical names and `display_name` holds the optional override. Back up the database and run schema initialization during a maintenance/deployment window; column widening and index creation can lock populated tables.

Job history uses `GET /printing/v1/admin/jobs?limit=200&before=<nextCursor>` with opaque keyset cursors and a maximum 200 rows per page. Claims read one candidate, pending inspection reads 50, and all these queries project metadata without fetching stored documents. Native dashboard lists already cap attention rows at 10 per category and recent work at 25. Dispatch/history indexes support growing histories; the synthetic 10,000-row regression test checks bounded pages and claim correctness, **not** a certified 10,000-print/day throughput measurement. Driver speed, document size, database hardware and retention policy still determine capacity. Document cleanup follows the configured retention policy and host schedule; the audit does not run a live purge or delete job history.

```java
PrintingService printing = new PrintingService(dataSource, tenantId);
printing.initialize();
new PrintingQBitProducer().withConfig(new PrintingQBitConfig()
   .withBackendName("appRdbms")
   .withStorageTableName("printing_documents") // existing dedicated S3 storage table
   .withSchedulerName("appScheduler")          // existing host scheduler
   .withService(printing)).produce(qInstance, "printing");
server.withAdditionalRouteProviders(List.of(new PrintingRoutes(printing, context -> hostPrintingAdminGuard(context))));
```

`hostPrintingAdminGuard` is the host's own authenticated administrator policy, not a supplied library method. The service is bound to one tenant; use a separate host service instance for each tenant. Device token verification and job ownership are enforced on every device operation. Management service methods are intended for trusted application code; enforce user/facility authorization before calling them.

The storage/scheduler options above are included in 0.1.4-rc.1. Configure a dedicated private printing bucket for the customer/environment through existing QQQ S3 metadata; no bucket credentials belong in printing forms. Admin retention defaults to 365 days after successful completion. Scheduled cleanup handles bounded batches every five minutes and preserves active, failed and uncertain work plus all receipts/history. Without a configured storage binding, legacy database documents remain supported; without a host scheduler, automatic cleanup does not run. Review [storage configuration, bucket isolation and S3 versioning](docs/STORAGE-AND-RETENTION.md) before deployment.

Enrollment uses a named facility selector. Set `withFacilityPossibleValueSourceName("your_existing_facility_source")` to reuse an already registered host source and its record security. Without it, the standalone choice is **Default facility**. The backend validates the selection under the current user's permissions before issuing an enrollment code; typing or forging an unknown/foreign-tenant ID cannot bypass that check. Existing stored facility IDs are preserved; configure their original source to resolve their names.

For native QQQ screens, grant `printing_admin` and populate the session's `printing_tenant` security key with the tenant ID. The Printing home shows device health, queue status and work needing attention, with Devices, Printers and Print jobs beneath it. It nests under an existing `admin` app when available. Processes are dashboard/table/record actions, not navigation entries. Secret/token and document columns are excluded from metadata. Table mutations are rejected; use the service/processes instead.

Typical setup: run **Enroll a print device** from Printing home or Print devices, enroll the Windows connector, and select local queues to expose in its companion/tray. Use **Set printer approval** to choose a named device and one of its printers. Test-print forms offer only exposed, approved printers supporting the chosen format. Device and printer choices respect the current user's tenant access.

For label printers, configure the machine's **Windows Printing Defaults** for the actual stock size and print method before testing PDFs through the service. A test profile selects document size and ZPL resolution; it does not change the Windows driver or printer's media configuration. Our 300 dpi Zebra required 4 × 6-inch stock and Thermal Direct instead of its previous 4.09 × 2-inch Thermal Transfer defaults. After correcting those settings and resuming the printer, the user confirmed the built-in PDF label, alongside the already confirmed ZPL label and laser PDF. Barcode scanning and measured alignment remain separate checks; `SPOOLED` alone is not proof of physical output.

On Printers, select one or more rows or open a printer record to **Approve printers**, **Disable printers**, **Print a test page**, or **Print a test label**. Standard QQQ review/result screens show the selected scope; preview does not print. Each selected printer is read again under the current user's permissions before execution. Jobs display the related printer and device names; approval, availability, format and status fields have named choices.

Metadata names follow QBit conventions: tables and data sources use `printing_*`, while processes/widgets use `printing...`. The initial namespace is fixed to `printing`. The QBit preserves the host's `QBrandingMetaData`; customer identity belongs in the host branding, not a custom QBit build.

Two independent settings govern dispatch: `available` means the queue is present in the latest locally selected inventory; `enabled` means a QQQ administrator approved it. New submissions and claims require both. Removing a queue from local selection makes it unavailable without deleting its approval/history. An empty inventory withdraws all queues. Withdrawing a queue cannot retract a job already accepted by Windows.

Application code calls `printing.submit(JSONObject)` with the shared `SubmitJobRequest` fields. A stable business idempotency key returns the same job when retried; changing document/options/target under the same key fails. PDF is sent as bytes encoded once in base64; ZPL is UTF-8 printer-ready bytes. Default decoded limits are 100 MiB for PDF and 20 MiB for ZPL, both configurable in the native Printing connection and limits process. Queue approval and format compatibility are checked before insertion.

```java
JSONObject job = printing.submit(new JSONObject()
   .put("deviceId", deviceId)
   .put("printerId", printerId)
   .put("title", "Packing slip")
   .put("format", "PDF")
   .put("contentBase64", Base64.getEncoder().encodeToString(pdfBytes))
   .put("idempotencyKey", "shipment:" + shipmentId + ":packing-slip:v1")
   .put("options", new JSONObject().put("copies", 1).put("scale", "ACTUAL_SIZE")));
String printJobId = job.getString("jobId");
```

For terminal and web clients the QBit also offers ready-made print processes: `PrintingRecordProcesses.registerAll(instance)` (after all host tables are added) puts **Print** on every readable table, rendering a record sheet from the table's sections and child lists; `printingPrintTableList` prints a grid of any table (columns, filter, sort, page or all rows, paper and orientation); `printingPrintText` prints text such as a screen capture. Opt a table out with `PrintingTableSettings().withPrintable(false)`. Each returns the queued job id in `printingJobIds`.

The returned ID identifies durable queue acceptance. Check job status asynchronously. The [protocol v1 specification](https://github.com/QRun-IO/qqq-print-protocol/blob/f977bd01b62ceb7539190fd2861809537fcf504c/README.md) defines HTTP endpoints, JSON fields, integrity checks and lifecycle semantics.

For durable QQQ jobs register `PrintEnqueueEffectTarget` as an `IDEMPOTENT_EXTERNAL` target in `DurableJobsMetaData`, then emit the submit-request payload. The target replaces the payload idempotency key with the durable effect key. Its SENT result means accepted into the queue, not physical output. This implementation uses its own JDBC transaction; do not register it as LOCAL_TX. Authorize the destination in the emitting process before writing the effect. QQQ's durable dispatcher runs with a `QSystemUserSession` and does not propagate the user's tenant keys. For that trusted outbox path only, the target resolves the service configured on the dispatcher's current QQQ instance; that service fixes the tenant and rejects devices belonging to another tenant. The payload cannot choose a service or tenant. A cached target resolves the current instance on every call. Inline user calls retain the `printing_tenant` scope check, and native management processes (including enrollment) still require both administrator permission and matching tenant scope. Do not expose direct effect emission through an arbitrary user-supplied target/payload endpoint.

## Deliberate first-version limits

The implemented path targets an explicit device/printer. Logical destination bindings, cancellation, reprint workflow, automatic reconciliation, clustered throughput optimization, retention and alerts are follow-ups. Documents are durably stored as base64 in the database for this first path; object-storage integration is deferred. Custom paper dimensions are rejected; configure the Windows queue. Received/submitting jobs are not automatically offered again. The queue records SPOOLED honestly and does not assert physical delivery.

The protocol demo and synthetic native UI preview are separate local hosts. Attaching the QBit to a deployed application and its real authentication/facility policy remains a host integration task. PostgreSQL behavior, production deployment, upgrades, cancellation/reprint and broad printer-driver coverage are not certified by the initial H2/laser tests.

## Verified initial path

On 2026-10-29, both the development Windows connector and the installed LocalSystem service printed a PDF to a local laser printer. The user confirmed both physical pages. MSI installation/start and temporary uninstall passed. Zebra hardware testing is deferred until a Zebra is connected. A SPOOLED status alone is never used as proof of physical output.

## CI and releases

Pull requests and `main` pushes run Java 21/Maven wrapper verification with the immutable sibling protocol checkout. A tag matching the POM version runs build/tests and the secrets, workflow, dependency and malicious-package security gates before publishing the tested JAR, POM and `SHA256SUMS` as GitHub release assets. Build verification enables the `ui-demo` profile used by the local host. Maven generates its selected compile/runtime graph as a CycloneDX SBOM, including frontend assets. Trivy and OSV scan that graph; a guard rejects missing or different package versions, missing frontend assets, and empty inventories. CI retains the resolved graph and scanner evidence. OSV uses `--all-packages`; its pinned 2.6.0 reporter [omits Maven groups](https://github.com/google/osv-scanner/issues/3099), so the guard checks every reported artifact/version and rejects ambiguous names while the verified input retains full package URLs. Hyphenated versions create prereleases. The packaged JAR includes the repository LICENSE and NOTICE under `META-INF`, alongside the existing font license and diagnostic assets. The workflow does not publish to Maven Central, deploy a QQQ application or print to hardware. The [0.1.0-rc.1 release](https://github.com/QRun-IO/qbit-printing/releases/tag/v0.1.0-rc.1) passed these hosted gates.

## Printing home

The admin-only Printing home uses stock QQQ widgets: a purpose/scope/help header, actionable exceptions, four health tiles, and a full-width pending/recent work list. Counts and record links use QQQ queries under the viewer's tenant. Device disconnection means no heartbeat for 90 seconds; job failure tiles cover jobs created in the past 24 hours. Older failures remain in Print jobs. An empty installation explains enrollment and printer setup.

This RC implements the happy path of the [QQQ dashboard standard](https://github.com/QRun-IO/qqq/blob/main/docs/DASHBOARD-STANDARD.md), with explicit remaining gaps: RC.20 carries role/priority hints in widget default values; attention uses scoped database queries and 60-second refresh rather than the core live attention feed; dismiss/snooze, interactive URL-backed page scope, and stale/retry presentation depend on further core/frontend integration. It has not passed the standard's full responsive/accessibility audit. No custom frontend or HTML dashboard is used.

### Operator actions (0.1.1-rc.1)

The Windows companion can request test prints and explicit reprints through device-scoped authenticated routes. The same printer approval/exposure rules apply. Upgrade the QBit and connector together for these additive protocol features. Reprints create a new job; uncertain/spooled originals require duplicate-output consent and remain unchanged.

QQQ's **Print jobs** table and records expose **Print again** and **Cancel queued jobs** using native selection/review/result screens. Cancellation is allowed only before dispatch; later work must be inspected in the Windows queue. The actions require printing administration permission and the configured tenant context.

## Warehouse diagnostic pages

All built-in printer test actions share `PrintingTestDocuments`: QQQ branding, the official [Query dog mascot](https://qrun.io/query), large warehouse-style identifiers and embedded JetBrains Mono Bold with a dotted zero and distinct 1/I/l. Query's original artwork and aspect ratio are preserved in PDF and thermal output; [asset provenance](src/main/resources/com/kingsrook/qbits/printing/images/README.md) ships with the resource. The font is included under SIL OFL 1.1; its license and provenance ship in the JAR. PDF embeds a font subset. ZPL renders the same glyphs into document-local, row-aligned graphics below Zebra's 99,999-byte per-field limit; it does not download or save a printer font.

Native record and selected-table actions expose a possible-value stock/resolution selector. Letter and A4 are PDF-only. Label profiles are 4 × 6 inches at 203, 300 or 600 dpi; PDF label output is always 101.6 × 152.4 mm. Select matching stock and printer resolution. Bulk actions apply the chosen profile to every selected destination, so group compatible printers. The document does not reconfigure Windows stock, calibrate the printer or save device configuration; ZPL width/length are transient format settings.

The device test-print API accepts optional `testProfile` with the same choices. Existing clients default to Letter PDF or 203-dpi 4 × 6 ZPL. Invalid profiles fail before enqueueing. Test actions use the ordinary durable queue and existing printer exposure/approval rules; discovery never automatically prints. A ZPL barcode, glyph comparisons and size/alignment marks support physical inspection. Rendering previews and spool acceptance do not prove paper or scanner quality.
