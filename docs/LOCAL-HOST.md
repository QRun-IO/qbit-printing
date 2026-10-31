# Authenticated local QQQ printing host

`com.kingsrook.qbits.printing.PrintingLocalHost` runs real QQQ screens and the printing protocol together. Native sign-in verifies a configured account with QQQ's table-based authentication. Only that verified account receives `printing_admin` and the configured `printing_tenant`. Authentication/session tables are protected by QQQ's system-table permissions, and passwords are stored only as native password hashes. This host always binds to `127.0.0.1`; remote deployment needs a separately configured QQQ application and HTTPS ingress.

## Three distinct launch targets

| Main class | Data and authentication | Purpose |
| --- | --- | --- |
| `PrintingDemo` | Durable H2, bearer-protected management API, no native QQQ UI | Protocol/hardware development |
| `PrintingUiDemo` | Isolated in-memory synthetic data, mock administrator, no device routes | Native UI demonstration only |
| `PrintingLocalHost` | Durable H2 shared by native QQQ and device routes, real password/session authentication | Authenticated local application |

The local host adds its own `printing_admin_user` and `printing_admin_session` tables. It preserves existing printing tables, device credential hashes, receipt history and tenant identity. Physical table/column names are mapped to the existing H2 case convention; it does not rename an uppercase database or change `DATABASE_TO_UPPER`. Every host instance uses a distinct internal backend name to avoid cross-instance provider-cache collisions in QQQ RC20.

## Required configuration

| Environment variable | Meaning |
| --- | --- |
| `QQQ_PRINT_JDBC_URL` | Required `jdbc:h2:file:` URL. Use an absolute private path and `;IFEXISTS=TRUE` when upgrading an existing paired database. |
| `QQQ_PRINT_ADMIN_TOKEN` | Existing management bearer token, at least 24 characters. Separate from the browser password. |
| `QQQ_PRINT_UI_USERNAME` | Explicit local administrator username, up to 200 characters, with no colon/control characters. |
| `QQQ_PRINT_UI_INITIAL_PASSWORD` | Required only when the authentication user table is empty; 16–1024 characters. Existing passwords are never reset by this variable. Omit it after bootstrap. |
| `QQQ_PRINT_TENANT` | Default `demo`; preserve the exact tenant used by the paired service. |
| `QQQ_PRINT_PORT` | Default `18081`; loopback binding is mandatory. |
| `QQQ_PRINT_DB_USER` / `QQQ_PRINT_DB_PASSWORD` | Existing H2 credentials; defaults `sa` / empty for compatibility with the development database. Protect the database file with host filesystem permissions. |

There is no default browser password and no account chosen automatically. Startup fails when the configured username differs from an initialized account. A password reset or account change needs an explicit administrative procedure; restarting with new environment values does not perform one. Do not place passwords or tokens in source files, shell history, command arguments or shared logs. Use the host's existing protected credential-loading mechanism. Bootstrap credentials are not retained in the application's settings after hashing.

## Build and run

Use Java 21 and the Maven wrapper. The `ui-demo` profile supplies the real frontend-next runtime assets for both UI-capable launch targets:

```powershell
.\mvnw.cmd -Pui-demo verify dependency:build-classpath '-Dmdep.outputFile=target/local-host-classpath.txt'
$dependencies = (Get-Content target/local-host-classpath.txt -Raw).Trim()
& "$env:JAVA_HOME/bin/java.exe" -cp "target/classes;$dependencies" com.kingsrook.qbits.printing.PrintingLocalHost
```

Set the required environment values securely before launch. Maven contract tests require the documented sibling protocol fixture checkout or `printing.protocol.fixtures` override. Browse to `http://localhost:18081/` (or the configured port) and use the native username/password screen. The existing Windows connector continues using the same server origin and device token; it does not receive the browser password or management token.

This command runs the host in the foreground. `QApplicationLauncher` owns graceful HTTP/scheduler shutdown. It does not install an operating-system service or auto-start registration; the deploying operator must provide supervision, protected credential loading, logs and backups for unattended use.

## Cut over an existing paired protocol server

1. Identify the exact existing database, tenant, management credential and connector URL. Inspect all pending and uncertain jobs through bounded management pages before a maintenance window.
2. Stop only that paired Java host. Back up its closed H2 database and protected configuration. Never copy a live H2 file and treat the copy as a guaranteed consistent backup.
3. Validate the new host against a separate backup copy and isolated port first. Keep device transport unreachable by real agents during that check; do not enqueue tests or seed synthetic work in the real database.
4. Provision the explicit browser account, then start the local host with the original database, tenant and port. Use `IFEXISTS=TRUE` to prevent a typo silently creating an empty database. The service's additive schema initialization runs during startup; older writers must remain stopped.
5. Verify sign-in, paired records in native Devices/Printers/Print jobs, connector heartbeat, named actions, retention settings and logout. Existing device enrollment must remain intact. Do not infer physical printing from queue acceptance.

Existing facility IDs are retained. The standalone enrollment form offers the named **Default facility** for new enrollment. This local host does not invent names for existing facility codes or import a production facility directory; a full application should supply its actual facility possible-value source.

## Browser and API boundaries

QQQ supplies password verification, failed-sign-in throttling, HttpOnly sessions, session expiry and logout. The local authorization subclass calls native verification first and maps only the configured account to printing permissions. Other valid accounts cannot gain those permissions. Native QQQ permission and tenant checks still apply to every table/process.

The host rejects non-loopback Host headers, cross-origin Origin headers and cross-site browser requests. Cookie-authenticated unsafe requests require an exact same-origin Origin header, including logout and native process actions. Explicit bearer/device API calls without ambient cookies remain usable from the connector. Use one loopback hostname consistently in the browser; `localhost` and `127.0.0.1` are different browser origins. These controls do not turn a local HTTP host into an Internet-facing service.

## Retention, storage and remaining deployment work

The native Quartz scheduler runs the existing bounded cleanup process every five minutes while the host runs. Settings and cleanup observations are persisted in the same database; the default is 365 days after successful completion. Active, failed, uncertain and unknown-completion work remains protected. Scheduler registration is rebuilt from QQQ metadata on restart; no new ad hoc timer or scheduler database is introduced.

This local host deliberately leaves the S3 binding unconfigured. Native settings report legacy database storage. New and existing documents therefore remain in the database; merely running this host does not move documents to S3. A deployed application must supply its existing dedicated `printing_*` S3 table/backend and scheduler configuration, with bucket/IAM/tenant separation described in [storage and retention](STORAGE-AND-RETENTION.md). The host must retain historical storage bindings when changing configuration.

Remote HTTPS deployment, real S3/IAM, production OAuth roles, PostgreSQL and operating-system supervision remain separate qualification work. Record Files source-version/actor integration remains pending its concrete public contract; standalone printing delivery storage must not own or delete canonical attachments.
