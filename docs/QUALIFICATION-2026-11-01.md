# Server qualification — 2026-11-01

The published server **0.3.0-rc.1** and published Windows **0.3.1-rc.1** Core runtime
completed the following isolated qualification. No live server, installed service,
real document, physical printer or external cloud bucket was changed.

## Network workload

These measurements describe the earlier released-artifact fixture runs. The
portable [Docker developer tooling](../dev/README.md) records its own per-run
measurements and includes separate default smoke and opt-in burst commands.

The existing Java/.NET interoperability fixture was extended with synthetic work.
It used the actual QQQ table-authenticated host, native session/device binding,
WebSocket channel, released Core `PrintWorker`, HTTPS-client code over explicitly
allowed loopback HTTP, file downloads, and WebSocket receipt acknowledgements.
Only the physical printer and local fixture journal were substitutes. The server
persisted real SQL jobs and receipts. Admission limits remained enabled.

| Check | Observed result |
| --- | --- |
| Burst workload | 10,000 jobs; two tenants; eight devices |
| Documents | 1,021-byte synthetic ZPL; exact downloaded bytes checked |
| Completion | 10,000 SPOOLED rows; 30,000 durable event rows; no pending work |
| Replay | 400 submit and 400 receipt replays; no duplicates |
| Isolation | Foreign device binding and tenant job access refused |
| Accounting | Retained bytes exact; no orphan upload rows |
| Duration | 96.12 seconds including setup; about 104 jobs/second |
| Maximum backlog | 7,798 committed jobs; final backlog zero |
| Working set | Server peak 680 MiB; client peak approximately 85 MiB |
| Cold persistence | 10,000 rows present; read-only check left DB unchanged |

Burst enqueue-to-terminal-ACK latency was p50 43.30 seconds, p95 62.84 seconds,
p99 64.66 seconds. These values include intentional queue backlog; they are not
idle notification latency.

A separate **40-job one-at-a-time** run measured:

| Interval | Median | 95th percentile |
| --- | ---: | ---: |
| Received notification to claim completion | 8.72 ms | 27.18 ms |
| Received notification to verified download completion | 29.74 ms | 49.07 ms |
| Enqueue to durable terminal receipt acknowledgement | 49.65 ms | 76.56 ms |

The fixture does not periodically poll for jobs. A received notification wakes a
finite claim/drain operation. Liveness PING messages do not query jobs. These are
HTTP/WS loopback results, **not HTTPS/WSS, remote network, physical print-speed,
large-document sustained throughput, or long-duration soak qualification**.
The fixture journal is not the installed Windows SQLite journal; its recovery
behavior is covered separately by the Windows tests.

## Existing storage path, current release

The existing S3 qualification harness was rerun against this exact published
server and its unchanged 204-JAR runtime. All **65 checks / six document round
trips** passed through native QQQ `StorageAction`, `S3StorageAction`, and AWS SDK
against one disposable local MinIO container. The run includes actual multipart
upload above 5 MiB, exact length/hash/MIME, tenant namespaces, immutable-reference
validation, stable replay, completion-age retention, and protection of active,
failed, uncertain, fresh, foreign and unrelated objects. The owned container and
its ephemeral data were removed after identity verification.

This is S3-compatible data-plane evidence. It does not qualify AWS IAM, remote
TLS, bucket policies, versioning/Object Lock, or a configured production storage
target. The existing platform-specific key-layout binding guard remains required;
no shared QQQ library or cross-OS migration behavior was changed.

## Repeatable service regression

`PrintingWorkloadTest` is an opt-in file-backed regression, separate from the
network fixture. It runs 10,000 real service lifecycles through four pooled SQL
connections, tests replay and tenant pagination, verifies exact event/byte counts,
and closes every connection before reopening without schema initialization.

```powershell
./mvnw.cmd -Pui-demo -Dprinting.qualification.load=true verify
```

The protocol fixtures must be present at the usual sibling checkout; alternatively
set `-Dprinting.protocol.fixtures=/path/to/reviewed/fixtures`.
The full gate passed **119 tests, zero skips and zero Checkstyle violations**.
A subsequent exact event-count assertion improvement passed its focused workload
rerun. Default builds skip this explicit workload test; ordinary regression tests
remain unchanged. The separate development workflow runs cross-platform script
checks and the real 40-job/S3 Docker smoke on pull requests and main pushes; its
experimental native-auth run is manual. Hosted results must be checked on the
submitted commit. No application implementation or dependency changed.

## Scope boundaries

The user's current development/test target is local Docker. AWS/account/IAM or
remote deployment provisioning is not a prerequisite for this change.

The separate [experimental native-auth profile](../dev/native-auth/README.md)
passed real Authentik login, denied-user/wrong-Origin checks, logout, short token
renewal and provider revocation using container-private HTTPS and the pinned
unreleased QQQ API 1.9 candidate. It does not upgrade this regression's released
RC.20 dependency or qualify the installed Windows browser callback. The workload
and S3 measurements above remain HTTP/WS and MinIO evidence, respectively.

Long-duration soak, mid-session account/role changes and sustained physical
throughput remain separate from these bounded tests and previously confirmed
individual physical prints. No application implementation, dependency or
published package version changes in this test/developer-tooling change.
