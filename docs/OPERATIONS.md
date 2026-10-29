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

Documents are stored as base64 in the database. Set a host-specific retention and access policy before deploying sensitive packing slips or shipping labels. The first release has no retention scheduler or object-storage backend.

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

Initial checks cover H2/native/HTTP tests and user-confirmed laser output from the development connector and installed Windows service. Follow-up qualification includes Zebra hardware, PostgreSQL, production host authentication, upgrade/migration paths, multiple QQQ nodes, prolonged disconnects and a broader printer/driver set. Current prerelease artifacts must not be described as certifying those scenarios.
