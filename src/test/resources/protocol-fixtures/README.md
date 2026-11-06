<!--
  Copyright 2026 Kingsrook, LLC
  SPDX-License-Identifier: Apache-2.0
-->
# Protocol fixtures used by PrintingContractTest

Unaltered copies of files from the `fixtures/` directory of the canonical `qqq-print-protocol` repository
at commit `5005f3c9e27053b6c327bb923695012832c0fdcc` (protocol 1.1.0, the commit this source pins; the files below are unchanged since `42c0d249f81395f00408ec0f92638dcbbf5deb03`), so the contract tests run
without a sibling checkout. Only the files the test reads are copied:

- `action-refresh-inventory.request.json`
- `action-revoke.request.json`
- `approval.request.json`
- `control-inventory-refreshed.receipt.json`
- `control-poll.request.json`
- `create-enrollment.request.json`
- `device-retry.request.json`
- `device-test.request.json`
- `enroll.request.json`
- `heartbeat.request.json`
- `printers.request.json`
- `received.event.json`
- `spooled.event.json`
- `submit.request.json`
- `submitting.event.json`
- `test-label.zpl`

To check these against another protocol revision, run the tests with
`-Dprinting.protocol.fixtures=<path to that checkout's fixtures>`. When the pinned commit changes, replace these
files from that commit's `fixtures/` directory.
