# Local Docker development

Use this repository to start a separate QQQ application or run synthetic printing
checks. Nothing imports an installed connector's pairing, accesses physical
printers, or needs a cloud account. Docker Compose and PowerShell 7.4+ are required;
Git identifies the checkout. Docker must support Linux/amd64 containers. The first
build downloads pinned public images, Maven dependencies and the verified Windows
Core source archive. Java and .NET run inside Docker; neither host SDK is required.

## Start, test and stop

From the repository root, the default command runs 65 S3 checks and 40 network
jobs, saves protected reports, and removes its containers and synthetic volumes:

```powershell
pwsh ./dev/Invoke-Dev.ps1 -Action Test
```

For a persistent development application, start it and keep the state-directory
path printed by the command:

```powershell
pwsh ./dev/Invoke-Dev.ps1 -Action Start
pwsh ./dev/Invoke-Dev.ps1 -Action Test -StateDirectory '<printed state directory>'
pwsh ./dev/Invoke-Dev.ps1 -Action Stop -StateDirectory '<printed state directory>'
```

Open `http://127.0.0.1:19081`. The standard QQQ TABLE login username is `developer`.
Read `QQQ_ADMIN_PASSWORD` from the generated, protected `compose.env` locally;
never paste that file into an issue or commit it. There is no default password.
The host provides native printing screens, settings, a dedicated MinIO document
bucket and scheduled retention. No printer is enrolled automatically in this host.
The test workload uses a separate disposable database and object store.

Add `-Authentik` when creating an environment or running a fresh test to include
real Authentik/PostgreSQL. See [the provider checks and remaining native grant
prerequisite](AUTHENTIK.md). Provider discovery does not establish successful OIDC
user login or refresh; the current QQQ RC.20 API lacks that native-client grant.

```powershell
pwsh ./dev/Invoke-Dev.ps1 -Action Test -Authentik
pwsh ./dev/Invoke-Dev.ps1 -Action Test -Workload 10000
```

The optional 10,000-job run keeps admission limits enabled, uses eight synthetic
clients across two tenants, and reports client process memory, latency and
receipt counts. It is a bounded burst, not a day-long soak or physical throughput
claim. The default 40-job run measures idle event delivery one job at a time.

## Isolation and cleanup

Each environment has a unique `qbit-printing-dev-<random>` Compose project and
matching ownership labels. All published ports bind only to `127.0.0.1`: QQQ19081,
MinIO19095 and optional Authentik19010. Override them with `-QqqPort`, `-MinioPort`
and `-AuthentikPort` when creating a new environment. Occupied ports fail startup;
the command never stops another service to free them. PostgreSQL is not published.

State defaults to the current user's application-data directory, outside the
checkout, with private filesystem permissions. A custom `-StateDirectory` must
also be outside the checkout. It holds generated fixture credentials, frozen
Compose files, ownership records and sanitized result files. Docker administrators
can inspect container configuration; use these credentials only for this fixture.

`Stop` validates container, network and volume ownership before removing that
project's resources. **It deletes that environment's synthetic databases and
objects.** It leaves reports and ordinary image/build caches in place and never
runs a global prune. A fresh one-command `Test` performs this cleanup in `finally`,
including on failure. Tests against an existing environment remove only their
three test containers; finish with `Stop` to remove its disposable test volume and
the persistent development environment. Do not edit its frozen ownership files.

Images are pinned by digest in Compose/Dockerfiles. Containers have bounded CPU
and memory, no automatic restart, and no Docker socket. The default profile has
no host credential or source mounts. QQQ/test containers additionally cap process
counts. Builds copy
only explicit source inputs. The QQQ and MinIO containers share their own network
namespace so the existing loopback S3 backend can be used without relaxing
production endpoint validation.

## What the results mean

- The S3 fixture exercises the actual QQQ `StorageAction`/S3 backend and AWS SDK,
  including a multipart-size document, hashes, tenant namespaces, immutable
  binding checks, retention and unrelated-object preservation.
- The workload uses the real current Java server and pinned Windows Core client:
  native TABLE authentication, outbound WebSocket notifications, event-driven
  claims, document downloads and durable server receipts. A fake file printer
  verifies bytes and rejects duplicate submission. It cannot produce paper.
- The fixture's client receipt journal is intentionally simple; these results do
  not replace production SQLite crash-recovery tests. MinIO fixture credentials
  are unrestricted within their owned store and do not qualify production IAM.
- Connections here are HTTP/WebSocket on an isolated local project. TLS, provider
  browser consent/renewal, real deployment policies and hardware behavior are
  separate qualifications. No silent bearer-authentication fallback is added.

Results include `result.json`, `server-result.json`, `s3-results.json` and
`execution.json` under a run directory. The persistent host also writes
`qqq-result.json`; optional provider checks write `auth-result.json`. Treat raw
fixture logs as private even though only synthetic accounts/documents are used.

The client Dockerfile pins commit `346fc285198908c47a2ee9db7b9adb53d5e88c3c`
and verifies its source archive SHA-256 before compilation. The Java image builds
this checkout, including local edits, and records its Git HEAD for context; a Git
HEAD alone does not certify a dirty tree as that released artifact. This tooling
is outside the production JAR and adds no application runtime dependency.

## Script regressions

With Pester 5 installed, the initializer and cleanup-ownership tests need no Docker
or network. Disable Pester's unrelated registry fixture on Windows:

```powershell
$configuration = New-PesterConfiguration
$configuration.Run.Path = './dev/tests'
$configuration.Run.Exit = $true
$configuration.TestRegistry.Enabled = $false
Invoke-Pester -Configuration $configuration
```

## Experimental native sign-in

The separate [native sign-in profile](native-auth/README.md) builds an explicitly
selected upstream candidate and uses container-private TLS trust. It mounts only
public Caddy configuration and specific protected fixture input/result files.
It is opt-in, Test-only, and does not change the default released QQQ dependency.

## Continuous integration

The development workflow runs the Pester and PowerShell analysis checks on
Windows and Ubuntu for every pull request and main-branch push. Ubuntu also runs
the real 40-job/S3 Docker smoke. The longer experimental native-auth profile is
an explicit manual workflow option; it is not silently substituted for the
released dependency baseline.
