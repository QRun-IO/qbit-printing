# Local Authentik reference

Run the optional real-provider checks with Docker Compose and PowerShell 7.4+.
The pinned fixture images use Linux/amd64; other hosts need Docker's corresponding
Linux-container support or emulation:

```powershell
pwsh ./dev/Invoke-Dev.ps1 -Action Test -Authentik
```

The command creates a fresh, owned development project, runs the normal QQQ and
MinIO smoke test plus the Authentik reference checks, then removes that project's
containers and volumes. It does not operate the installed Windows connector,
existing Docker stacks or physical printers. It needs no cloud account.

For an environment that remains available for development, use `-Action Start`
with an explicit `-StateDirectory` outside the checkout. Use that same directory
with `-Action Test -Authentik`, then `-Action Stop` when finished. Stop destroys
only that owned project's synthetic databases and objects; retained private
reports are separate. See the [development command](Invoke-Dev.ps1) for port
overrides. Authentik defaults to `http://127.0.0.1:19010`;
its database port is not published. The QQQ host remains on standard TABLE
authentication.

## What the automated check establishes

- Real Authentik 2026.8.5 starts with a separate PostgreSQL 16 Alpine database.
- A synthetic confidential OAuth provider/application and development user are
  configured through Authentik's API. The callback list is explicit and grants
  are limited to authorization code and refresh. Access/refresh validity is
  two/ten minutes for a future bounded renewal test.
- Actual provider discovery has the expected issuer, same-origin endpoints and
  S256 capability. A valid state/S256 request with the registered callback
  continues to the provider login flow without following it. Changing only that
  callback to an unregistered redirect is rejected before any user login.
- The current QQQ RC.20 host advertises UI API 1.8 without `nativeSessionLogin`.
  That expected absence is recorded as a native-client prerequisite failure,
  not silently replaced by bearer authentication or an unpublished core.

`auth-result.json` distinguishes those checks from browser login, renewal and
the Windows callback, which are **not run**. Provider setup/discovery is not an
OIDC user-session integration test. No password login dialog is automated.

## Credentials and ownership

The common entrypoint creates a protected state directory outside the checkout.
`Invoke-Authentik.ps1 -Action Initialize` appends only its `AUTHENTIK_*` settings
to `compose.env`, preserving the QQQ/MinIO settings. It records synthetic
credentials in private `auth-secrets.json`; neither file belongs in Git, a bug
report or terminal output. Bootstrap uses a Django password hash. The temporary
bootstrap API token remains confined to this disposable project and disappears
with its database at cleanup. Do not reuse these accounts outside the fixture.

The Docker administrator can inspect container configuration. The scripts do not
print credentials or provider error bodies, follow redirects with an API token,
or request raw container logs. HTTP reads have a 15-second cancellation deadline
and a 512 KiB response limit. Startup has a bounded readiness loop. Initializer
replays retain credentials; conflicting or partial state is refused.

Compose pins cached/public image digests, not floating tags:

<!-- markdownlint-disable MD013 -->

| Component | Image digest |
| --- | --- |
| Authentik server/worker 2026.8.5 | `ghcr.io/goauthentik/server@sha256:602227a86455d6772e3d16b7d852713c4f864f40df5df894abd1285bb53979a5` |
| PostgreSQL 16 Alpine | `postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea` |

<!-- markdownlint-enable MD013 -->

There is no Redis requirement for this Authentik version, Docker socket mount,
outpost management, shared volume or automatic restart policy. Container and
volume labels identify the exact generated Compose project. The common cleanup
checks those labels before removing resources; it never performs a global prune.

## Separate native-session qualification

The Windows native path requires the centrally defined API 1.9 typed
`AUTHORIZATION_CODE_PKCE_SESSION` grant and trusted HTTPS on both QQQ and provider
origins. RC.20 does not supply that grant, and this HTTP development profile does
not weaken the client's HTTPS rules. The synthetic reference callback is only a
provider configuration value; no callback receiver or QQQ OAuth adapter is
claimed here. The TABLE host and installed pairing are unchanged.

The separate [experimental native profile](native-auth/README.md) builds an exact
public, unreleased QQQ candidate inside Docker and uses private container-only
HTTPS trust. On 2026-11-01 it passed actual provider login, native permission
denials, logout replay rejection, renewal after expiry and provider revocation
with the production Core client. Its synthetic HTTP driver and HTTPS callback
receiver do not qualify the installed Windows browser UI or private URI callback.

Production adoption still requires a compatible published central artifact
tuple. Provider refresh tokens stay on the server. The short lifetime test does
not prove months or years of unattended use. Do not route the private Windows
callback to a live companion whose connection must remain intact.

The setup follows the official [2026.8 Compose definition](https://goauthentik.io/version/2026.8/lifecycle/container/compose.yml),
[bootstrap contract](https://docs.goauthentik.io/install-config/automated-install/)
and [OAuth provider contract](https://docs.goauthentik.io/add-secure-apps/providers/oauth2/).
The repository supplies the repeatable local setup; provider/browser acceptance
is reported separately from TABLE authentication and printing workload tests.

## Initializer regression tests

With Pester 5 available, run `dev/tests/Authentik.Tests.ps1`. They use temporary
files only: no Docker, HTTP, installed service or registry access is required.
Disable Pester's unrelated registry fixture on Windows:

```powershell
$configuration = New-PesterConfiguration
$configuration.Run.Path = './dev/tests/Authentik.Tests.ps1'
$configuration.Run.Exit = $true
$configuration.TestRegistry.Enabled = $false
Invoke-Pester -Configuration $configuration
```
