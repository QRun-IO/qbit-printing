# Installation and release operations

The stable server library is [QQQ Printing 1.1.1](https://git.kof22.com/QRun-IO/qbit-printing/releases/tag/v1.1.1) (1.1.0 is superseded: its realtime upgrade fails behind an ingress that lowercases header names), paired with protocol 1.1.0 and Windows connector 1.2.1 (1.0.x/1.1.x connectors keep working on the unchanged 2.3 behaviour). It requires Java 21 and the QQQ 4.1.0-RC.22 dependency tuple, which is published only in the QRun-IO Gitea Maven registry. Its native printing extension is 2.4.0; the WebSocket subprotocol remains `qqq-printing.v1`. The previous stable library, [1.0.0](https://git.kof22.com/QRun-IO/qbit-printing/releases/tag/v1.0.0), remains available for rollback.

Gitea is primary for source, pull requests, CI, packages and server releases. This forge requires sign-in even for download links on public repositories. An HTML sign-in page is not an artifact. Use your existing approved forge identity; never commit its token or put it in a command line.

## Use the Maven registry

Configure the consuming application's existing Maven repository setup with repository ID `qrun-io` and URL `https://git.kof22.com/api/packages/QRun-IO/maven`. Enable releases and disable snapshots. Match that ID to a `<server>` entry in your private Maven `settings.xml`, using your existing managed username/token or encrypted Maven credentials. For example, environment references keep literal credentials out of the configuration:

```xml
<servers>
   <server>
      <id>qrun-io</id>
      <username>${env.GITEA_USERNAME}</username>
      <password>${env.GITEA_TOKEN}</password>
   </server>
</servers>
```

Merge this entry into the existing settings; preserve other servers, mirrors and profiles. Supply the environment through your approved credential loader. The credential needs package read access. Add `com.kingsrook.qbits:qbit-printing:1.1.1` as shown in the [README](../README.md#add-to-a-qqq-application). QQQ 4.1.0-RC.22 and the printing library both resolve from this registry; third-party dependencies still come from the Maven Central mirror until the registry proxies them.

## Download and verify release files

Sign in to Gitea and download all three assets from the stable release: `qbit-printing-1.1.1.jar`, `qbit-printing-1.1.1.pom` and `SHA256SUMS`. In their download directory:

```powershell
Get-Content ./SHA256SUMS | ForEach-Object {
   $hash, $name = $_ -split '  ', 2
   if((Get-FileHash -LiteralPath $name -Algorithm SHA256).Hash -ine $hash) {
      throw "Checksum mismatch: $name"
   }
}
```

On Linux, use `sha256sum -c SHA256SUMS`; on macOS, use `shasum -a 256 -c SHA256SUMS`. Install the verified JAR/POM locally with the README's `install:install-file` command if your application does not consume the registry directly. The library needs its normal Maven runtime dependencies; copying the JAR alone does not deploy a complete host.

## Maintainer release path

Since 1.1.2 the repository builds on the shared `KofTwentyTwo/ci-workflows` Maven workflow (`.gitea/workflows/build.yaml`, pinned to `@v1`): every push runs `-Pui-demo verify`; a `vX.Y.Z` tag runs the same tests once and deploys the JAR/POM to the QRun-IO Gitea Maven registry under exactly that version. The former caller's pinned protocol checkout is gone; `PrintingContractTest` reads the vendored copy in `src/test/resources/protocol-fixtures` (kept byte-identical to the reviewed protocol commit named in its README), which is also what local `verify` uses. Earlier releases (1.0.0, 1.1.0, 1.1.1) went through the protected `qrun-release-v1` channel and the trusted publisher; their evidence below stands.

1. Update the POM version (the tag must equal it), `qqq.version` if the runtime moves, and active documentation on a branch in Gitea. Open a normal pull request; the push build must be green.
2. Merge normally and wait for the green main build (verify only; main never publishes).
3. Create `v<POM version>` at the qualified main commit without moving an existing tag and push it. The tag build tests and deploys `com.kingsrook.qbits:qbit-printing:<version>` to the registry; a tag build that fails publishes nothing and the next attempt is the next patch version (tags are immutable).
4. Read the registry JAR/POM back with the approved forge identity, create the Gitea release on the tag, attach the same JAR/POM and a flat `SHA256SUMS`, read them back, then publish. Never replace different bytes under a released version.
5. Record the source, tag build, release and verified hashes here. Publication is separate from installing a host or accepting physical printer output.

For 1.1.1, source `2a561815fcec8b7c95e76bb4b36fba314f512027` (main; merge of PR 30, the realtime upgrade header-case fix), main run `5976` (verify, secrets scan, gate and post-publish succeeded), reactor artifact `4426`. The trusted publisher first refused the run because the 1.1.0 documentation merge had re-pinned the protocol fixture in `qrun-release.yaml`, changing the caller hash its enrollment verifies; the enrollment was re-reviewed in cluster-gitops (PRs 283 and 285) and the capture was then published unchanged. Registry files and release assets match the capture byte-for-byte. The JAR SHA-256 is `156720da6928f62d77101521a2953f6e1026d58d02e7fc7815a8c8fcbcedaefa`; the POM SHA-256 is `2fa451260a26f5b5ede30800d46c78050e223694d3d8dda640f9ca875d120ab0`.

For 1.1.0, source `eb2c16785d0723a47d4a22c68b8b04503108fc58` (main; merges of PR 25 printer approval and PR 28 printer/device removal), main run `5473` (verify, secrets scan, gate and post-publish all succeeded after the CI quota incident of the same afternoon), reactor artifact `4306`, release assets read back by the approved forge identity and matching the registry files byte-for-byte. The JAR SHA-256 is `3a51b7e7cf457cce5db16f138c4cc65b901730bda17ccaf1d3c714cf11d2bbf4`; the POM SHA-256 is `52e5a588e788bbe123dad574455493aea0a7df6cc473c78f15be09343048a5d4`.

For 1.0.0, source `2906c2bd5049924abd2114c8ef51a0ac9aac325e`, main run `3594`, reactor artifact `2741` and release `1356` were verified. Release assets and registry files matched byte-for-byte on authenticated readback. The JAR SHA-256 is `7ecb00e1f49272d6e885dc5f515bd4832aa52dd84e0613d9b6751f9e235a0961`; the POM SHA-256 is `8be7c30208a7fbb875dcebedb0ba6baf0e172aa0e7cf478a3d2863d1316ddf5f`.

GitHub remains a shadow/archive. GitHub Actions for this server repository and the protocol repository were retired on 2026-11-04. Their checked-in workflows and older release evidence are historical. The Windows repository retains its separate signing/update-feed workflow. Do not restart shadow workflows to publish a server release, claim GitHub attestations for 1.0.0, or imply the QBit was published to Maven Central.
