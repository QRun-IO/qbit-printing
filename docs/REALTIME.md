# Real-time printing host integration

Release candidate [0.3.0-rc.1](https://github.com/QRun-IO/qbit-printing/releases/tag/v0.3.0-rc.1)
implements printing extension **2.0.0** (wire envelope v1). Upgrade the server
before the matching Windows connector; a real-time connector rejects a server that advertises only extension
1.x. There is no timed job/control polling fallback.

## Connection and authorization

The Windows service initiates `wss://<qqq-origin>/qqq/v1/qbit/printing/device/realtime`
using subprotocol `qqq-printing.v1`. No facility listener or inbound firewall rule
is required. Plain WebSocket is allowed only for explicit loopback development.
Configure the cloud ingress to forward WebSocket upgrades, preserve the original
HTTPS origin and allow the configured heartbeat interval/timeout.

The service discovers authentication through the QQQ UI/UX API and uses the
standard current QQQ session, client channel and approved bound-device proof.
Credentials are not URL parameters. Device proof does not replace the user's
authority. The server validates the current session, user/tenant, channel policy
and operation permissions; socket establishment does not grant permanent rights.
Session expiry, logout, revocation and permission denial require correction or
sign-in through the configured QQQ provider. Reconnect cannot bypass them.

Register the route provider alongside the ordinary native QQQ endpoints:

```java
server.withAdditionalRouteProviders(List.of(existingHttpRoutes,
   new PrintingRealtimeRoutes(printingService)));
```

Use the **same PrintingService instance** in the QBit configuration and route
provider. `PrintingLocalHost.configure` shows the complete launcher integration.
The native HTTP claim route requires a connected socket that has applied the
current desired state and settings. Registering just the older HTTP provider is
insufficient. Close the provider when its host stops.

## Independent control and document lanes

The socket carries small JSON state, commands, inventory, heartbeats, job events
and durable receipts. A job notification triggers a finite HTTP claim drain;
an empty queue waits for another push. No timer repeatedly fetches claims or
commands. Heartbeats, session renewal and reconnect backoff are lifecycle work,
not job polling.

Documents use separate outbound same-origin HTTPS transfers. The Windows service
streams them to protected staging files and checks declared length and SHA-256
before native submission. The socket reader remains active during a slow download.
PDF rendering and ZPL spooling receive the verified file rather than a base64
payload in the control pipe. Limits bound staging, messages and concurrency.

Receipts remain on disk until the server acknowledges their committed IDs. A
reconnect resynchronizes current state/settings and replays retained receipts.
An interrupted native submission remains uncertain and is never automatically
reprinted. A successful spooler submission does not prove paper physically printed.

## Native administrator settings

Printing home links to two standard QQQ processes. They enforce current
administrator and tenant access on the backend as well as in metadata:

- **Printing connection and limits**: PDF/ZPL size, copies, staging space,
  concurrent downloads/prints, bandwidth, request/download timeouts, heartbeat,
  reconnect backoff/jitter, acknowledgment timeout and control message queues.
  Defaults are 100 MiB PDF, 20 MiB ZPL and one download/print lane. Saved policy
  changes have a durable revision and are pushed to connected devices.
- **Printing queue and storage limits**: device/tenant outstanding counts and
  bytes, retained bytes, hourly allocations and printer counts. New admissions
  use the persisted policy inside their SQL transaction. Reducing limits preserves
  existing jobs and history; it does not silently remove documents.

Fields use readable MiB/GiB/seconds with exact conversion, required values and
cross-field validation. Unsupported client ceilings fail explicitly rather than
silently clamping. Storage retention remains in the separate native retention
process, with a one-year default.

HTTP ingress/body limits remain host-owned. The local host derives its upload
body ceiling at startup from the persisted document limits (including base64
expansion for legacy submission). Raising a document limit above the running
transport ceiling requires a host restart or corresponding production ingress
configuration. Download streaming does not base64-expand documents.

## Host ownership and deployment boundary

Commit wakeups are emitted after SQL commit. A subscription exists before initial
state reconstruction, closing the empty-claim/reconnect notification race.
`PrintingRealtimeBus` is an explicit host-owned notification and connection
ownership seam. The default implementation is in-process; this source does **not**
provide a distributed broker or claim tested multi-node production support.

Use one writer/application host with its configured service/bus. Multiple route
providers sharing that explicit in-process bus fence a device's prior connection.
A multi-node deployment needs a host-provided shared implementation preserving
both post-commit notifications and single-channel ownership, and its own
qualification. Separate independent service instances do not magically publish
notifications to one another.

The existing `/printing/v1` routes remain explicit legacy diagnostic compatibility.
They are not a real-time fallback and cannot authorize a native bound device.
The full message and settings contract is in
[the shared protocol](https://github.com/QRun-IO/qqq-print-protocol).

## Printing API major upgrade

The extension is **2.0.0** because native HTTP claims now require a synchronized
WebSocket. A published 1.0 HTTP-only connector no longer meets that precondition;
this is a breaking behavior change even though request bodies remain wire v1.
The QQQ framework API `/qqq/v1`, extension path `/qbit/printing` and WebSocket
subprotocol `qqq-printing.v1` remain unchanged. Upgrade server and client together;
an older client must report upgrade-required instead of using polling fallback.
