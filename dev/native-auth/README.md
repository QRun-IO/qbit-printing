# Experimental native Authentik qualification

This optional profile tests an **unreleased upstream QQQ candidate**. It does
not replace the default released RC.20 TABLE/MinIO development environment,
install a connector, or qualify the Windows browser callback UI.

From the repository root, with Docker and PowerShell 7.4 or later:

```powershell
pwsh ./dev/Invoke-Dev.ps1 -Action Test -ExperimentalNativeAuth
```

The command creates a fresh owned project, builds the pinned sources, runs the
test, and removes its containers, network and synthetic database volumes in
`finally`. Protected input and sanitized result files remain outside the
checkout. Do not print or commit the private input or Compose environment.
An explicit fresh `-StateDirectory` preserves the same cleanup behavior.
Persistent `Start` and conversion of an existing standard environment are
refused for this profile.

The test uses actual Authentik login stages with generated synthetic accounts,
the standard QQQ OAuth2 code/session/refresh handlers, and the published .NET
Core PKCE/session client. The provider issues the authorization codes. The
client receives a real HTTPS callback and stores only the opaque QQQ session
in process; provider refresh tokens remain on the QQQ server.

The required checks are:

- Ordinary TLS rejects the fixture before its private CA is installed.
- Actual API discovery advertises the typed native session grant.
- Authorized real provider login resolves a server-derived native identity.
- A wrong-Origin cookie request and an authenticated subject without the
  printing permission are rejected.
- A logged-out QQQ session cannot be replayed.
- After the 20-second provider access grant expires, standard QQQ renewal
  obtains a new provider grant and preserves the principal and tenant.
- Revoking the synthetic subject's provider refresh grants prevents renewal
  after the next access-grant expiry.

Success is recorded only when every check passes. A failure report contains
the stage and exception type, without callback URLs, credentials or response
bodies. This short renewal test does not prove months of unattended operation.

## Isolation and source pins

The upstream source is
[`ecf0f550a0357cec6387c2b54efca128a405a629`](https://github.com/QRun-IO/qqq/commit/ecf0f550a0357cec6387c2b54efca128a405a629).
Its archive checksum is pinned in `Dockerfile.server`. Maven installs the
development build only into `/candidate-maven` inside the image; no host Maven
cache or installed runtime is mounted or changed. Printing source is compiled
against that candidate solely for this test.

The production .NET Core client is pinned to published source
[`346fc285198908c47a2ee9db7b9adb53d5e88c3c`](https://github.com/QRun-IO/qqq-print-agent/commit/346fc285198908c47a2ee9db7b9adb53d5e88c3c)
with a checked archive hash. Authentik, PostgreSQL, Caddy, Java and .NET images
are pinned by digest in the Compose files and Dockerfiles.

Caddy issues the three fixture HTTPS hostnames using its private local CA.
`skip_install_trust` prevents automatic trust installation. Only the public
root certificate is copied to the Java and .NET containers' trust stores;
ordinary chain and hostname validation remain enabled. CA private keys stay
in the TLS container's owned volume. No Windows or browser trust store changes
occur. See [Caddy's local HTTPS documentation](https://caddyserver.com/docs/automatic-https#local-https).

The HTTPS callback receiver is internal to the Compose network. It is separate
from the installed connector's private URI callback. No callback host port is
published. The profile mounts public Caddy configuration and the two specific
private input/result files, rather than a host home or credential directory.

The QQQ fixture uses fresh standard session, redirect-state and encrypted
refresh-token tables. Its customizer maps the verified provider's synthetic
`printing_admin` group to the existing native permission. It creates no device
pairing, job, print operation or fabricated user session. Released RC.20 lacks
the native-session grant; this experiment must stay opt-in until an appropriate
immutable upstream tuple is published and separately adopted.

## Recorded local qualification

On 2026-11-01 the full disposable profile passed all eight checks and exited zero.
Independent inventory confirmed zero owned containers, volumes and networks after
cleanup. This is evidence for the pinned experimental tuple above, not adoption
into the default released runtime or installed Windows UI acceptance.
