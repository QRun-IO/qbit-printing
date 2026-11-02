# Print document storage and retention

Status: audit implementation pending final verification and release. QQQ 4.1+ hosts provide the existing storage and scheduler metadata; this module does not create buckets or copy credentials into printing settings.

## Deployment boundary

Use a dedicated private printing bucket for each customer/environment, separate from general uploads, backups and unrelated QBits. Configure it through the host's existing QQQ S3 backend and a printing storage table. Bind that table with `PrintingQBitConfig.withStorageTableName(...)` and the existing scheduler with `withSchedulerName(...)`. Printing uses QQQ `StorageAction` for writing, reading and deleting objects. The Windows agent receives authenticated document bytes through QQQ and never receives S3 credentials or a bucket-wide URL.

Keep access keys, role credentials and endpoint configuration in the host's established secret/configuration mechanism. Prefer a deployment role restricted to this printing bucket. Permit only the object read/write/delete operations the storage adapter needs, scoped to its printing prefix; grant bucket-level listing only if the configured adapter requires it, with an appropriate prefix condition. Do not grant bucket deletion, ACL/policy administration, or arbitrary access to other buckets. AWS distinguishes bucket and object resource permissions in its [bucket policy examples](https://docs.aws.amazon.com/AmazonS3/latest/userguide/example-bucket-policies.html).

The code validates the selected S3 metadata, detects conflicting bucket bindings within the QQQ instance and uses tenant-specific object namespaces. Each job retains its storage table, destination identity and exact document reference. Retrieval and cleanup validate those values and fail closed if metadata is retargeted or the reference belongs to another tenant/job. Cleanup deletes exact eligible objects; it never lists and wipes a prefix. Tenant prefixes are an application isolation control, not a substitute for separately scoped IAM roles or separate customer buckets.

Application metadata cannot prove that a bucket is unused by another application or that deployed IAM/bucket policies are correct. Deployment qualification must check those external controls, public-access blocking, transport/encryption policy and the intended customer/environment boundary. No live bucket has been created or changed by the audit.

## S3 key layout and migration

QQQ filesystem 4.1.0-RC.20 builds S3 keys with the host filesystem separator. With empty base paths, Windows writes two leading backslashes before the logical `printing/<tenant-hash>/<job>.ext` reference; Linux writes the canonical reference. Same-platform object operations have been qualified in isolated S3-compatible storage. The logical reference alone is therefore insufficient for IAM prefix rules or migration between platforms.

Printing fingerprints the reviewed `qqq-s3-filesystem-v1` layout and the actual `File.separatorChar` code point (47 or 92), alongside the endpoint, bucket, region, path-style setting and backend/table base paths. Ordered JSON encoding preserves the identity component boundaries, including valid multiline base paths. A different layout rejects historical reads, new retries and cleanup with a destination-mismatch error before accessing those objects. New retries whose source binding is already incompatible fail before reserving another upload; the document loader also rechecks the binding when reading. An already-created retry still returns its idempotent receipt. Cleanup preserves content availability, retained-byte accounting and orphan reservations on mismatch.

This S3 feature is unreleased. Old experimental fingerprints without the layout marker are deliberately rejected, even on their original platform. Startup never rewrites those bindings or moves/deletes their objects. Preserve the original database and objects until an explicitly reviewed migration or disposal; do not replace hashes merely to bypass the guard. Inline database documents remain readable and can be explicitly retried into the configured destination without any automatic storage migration.

This guard does not make QQQ keys portable. The upstream owner must provide portable key construction and legacy-key handling. Adopting that change, including a change on the same operating system, requires an explicit layout-marker review and a migration plan for historical bindings and exact objects. The marker is a reviewed compatibility contract, not automatic detection of dependency implementation changes. Cross-platform migration and production IAM remain separate qualification work.

## Retained originals and delivery copies

Record Files owns original versions and attachments; document generation owns rendering. Printing owns a separate delivery payload and queue receipts. Never bind printing cleanup to a Record Files bucket or pass an original's storage key as a printing-owned reference. Printing expiration must not remove a source attachment or weaken its retention policy.

The current independent integration seam accepts bytes and a stable business idempotency key. An authorized immutable-version reader and source/job correlation contract are being coordinated with Record Files; that API is not yet implemented or assumed here. Printing currently checks device/destination authorization at dispatch, not later revocation of the source attachment's permissions. A shared adapter must define that boundary before promising revocation propagation.

The existing `PrintEnqueueEffectTarget` accepts base64 document bytes in the host's durable outbox. That outbox may retain another delivery copy outside this module's S3 cleanup policy. Hosts must manage its retention separately; do not claim that configuring printing S3 moves existing outbox payloads or all upstream copies into the printing bucket. The future retained-version adapter should carry authorized opaque source IDs/hash/length/media type and actor/correlation, not bucket URLs or duplicated document bytes. Direct byte submission stores the printing-owned payload through the configured storage backend without adding this host-outbox copy.

## Administration and scheduled cleanup

Printing administration exposes retention settings and a reviewed **Run cleanup** action. The default is **365 days after successful completion**. The storage destination is read-only in these forms: bucket changes require a reviewed deployment/storage migration, not typing a new backend ID into a cleanup form. The screen reports the effective storage scope, schedule, eligible backlog and last cleanup result.

When the host supplies a scheduler, maintenance runs every five minutes in bounded batches of at most 200. That permits a nominal 57,600 eligible documents per day before storage latency or failures; it is not measured production throughput. Changing a retention setting does not itself purge documents. Disabling cleanup preserves content. Without a configured scheduler there is no automatic job; the administration screen must show that condition.

Only eligible successful terminal jobs lose their document content. Active, failed or uncertain work remains protected. Job metadata, document hashes, receipts and idempotency records remain available for auditing and duplicate prevention. Legacy completion times are recovered only from durable successful events; unknown completion dates remain protected. Existing inline database documents remain readable; the initial storage integration does not silently migrate old bytes to S3. Reprinting a job whose document has been removed returns an explicit unavailable-document result.

## Failure and versioning behavior

External object storage and SQL cannot share one transaction. Cleanup must retain retryable state on failed deletion, and submission must account for an uploaded object whose database write fails. A process crash between object and database operations remains a recovery boundary; do not delete unknown objects merely because a prefix looks old. Review the recorded cleanup outcome/backlog and storage errors before intervening.

Submission persists an exact upload intent before uploading and removes it atomically with the queued job. Failed compensation leaves that intent for maintenance; abandoned intents become eligible after one hour. Maintenance uses a renewable one-hour lease and exact row locks, reusing one SQL connection rather than acquiring a second connection while holding the first. Interrupted multipart uploads may leave unfinished parts rather than an object: configure the dedicated bucket's `AbortIncompleteMultipartUpload` lifecycle separately. Deleting an object does not remove unfinished multipart parts, and this lifecycle must not expire current objects by age.

With S3 versioning, a normal delete can create a delete marker while retaining noncurrent bytes. Application cleanup therefore means the current document is no longer available through printing; it does not promise permanent erasure of all versions. Set an explicit noncurrent-version retention policy consistent with the customer's requirements and any Object Lock policy. See AWS's [expiration semantics](https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-expire-general-considerations.html) and [version lifecycle examples](https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-configuration-examples.html).

Do not attach a blanket 365-day current-object expiration rule to the printing prefix: S3 cannot see job state and could delete an active or uncertain job that the application deliberately protects. The application controls eligibility; any noncurrent-version cleanup is a separate deployment policy.
