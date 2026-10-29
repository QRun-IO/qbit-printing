# Security

Report vulnerabilities privately through [GitHub vulnerability reporting](https://github.com/QRun-IO/qbit-printing/security/advisories/new), or `contact@kingsrook.com` if that channel is unavailable. Include affected versions, a minimal synthetic reproduction and expected versus actual authorization behavior. Do not put enrollment codes, bearer credentials, customer documents or exploit details in public issues.

The current `0.1.0-rc.1` prerelease targets QQQ 4.1+. Fixes target the current prerelease; older QQQ versions are outside scope.

The host must provide authenticated management authorization and the correct tenant/facility context. Native QQQ operations require `printing_admin` and a matching `printing_tenant` session security value. Device credentials only authorize their device's queue/content/status operations. HTTPS is required for remote deployment; the supplied HTTP demos deliberately bind to loopback.

The UI demonstration uses a fixed mock identity and synthetic data and must remain local. It registers no connector transport routes. RAW ZPL is printer-control input: submit trusted generated labels, not arbitrary untrusted printer commands. Stored documents can contain personal information and need an application-specific retention policy.
