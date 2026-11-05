# Printing screens and operator workflow

The local preview runs the actual QQQ frontend with synthetic records. It has no device transport routes and cannot send its jobs to a real printer. Follow the README to start it; the default address is `http://127.0.0.1:18082`.

## Printing home

Open **Printing** in the navigation. The home presents the current tenant and refresh scope, actionable exceptions, device/printer/job health counts, and pending/recent work. Follow an exception or count to its filtered table, then open the affected record. Disconnected devices use a 90-second heartbeat threshold; recent failures cover the last 24 hours. Older failures remain available in Print jobs.

![Printing home with synthetic device, printer and job data](screenshots/printing-home.png)

## Enroll a facility computer

Choose **Enroll a print device** from Printing home or the Print devices table. Use the resulting short-lived enrollment code in the Windows companion's Connection settings. Once enrolled, select the installed printer queues to expose in the companion. Exposure is opt-in and persists in the service.

Open **Printers** in QQQ. The operational columns show the printer name, named print device, status, approval, local exposure, and last contact. Approval and exposure are independent: a printer must be both approved in QQQ and exposed on the computer before new jobs can be dispatched.

![Filtered printer table](screenshots/printers.png)

## Act on one printer or a selection

Select one or more printer rows and open **Actions**, or open an individual printer record and use its actions. Available operations include **Approve printers**, **Disable printers**, **Remove printers**, **Print a test page**, and **Print a test label**. The standard QQQ review screen describes the selection; reviewing does not print. Submitting a test action queues one job per selected eligible printer.

**Remove printers** archives the selection: each printer gets a **Removed at** time, loses approval and exposure, and disappears from the operator catalog, the connector's approval frame and the device/printer choices in the setup and test-print forms. Its record and job history stay. The Printers table has no server-side default filter, so add **Removed at is empty** to the Printers view you keep (the screenshots above predate this field) to hide archived rows; clear it to audit them. If the Windows companion shares the same queue again, the next inventory brings the printer back as exposed but not approved, so approve it again before it can print.

## Remove a device

Open a Print devices record or select rows and choose **Remove device**. The action requires the confirmation checkbox. It revokes the device's access permanently through the same control as **Revoke device access**, then marks the device and all of its printers removed. The device drops out of the Print devices view (add **Removed at is empty** there too), the setup and test-print device choices and every enrollment, claim and control path; its jobs remain in Print jobs and its control history keeps the REVOKE receipt. A removed device cannot be un-removed; enroll the computer again to create a new device. Retention cleanup eventually hard-deletes removed devices and printers that no job references.

![Actions on the selected printer](screenshots/printer-actions.png)

![The same actions on a printer record](screenshots/printer-record-actions.png)

![Review a test-label request](screenshots/test-label-review.png)

![Successful synthetic test-label process result](screenshots/test-label-result.png)

Use a PDF test page for the laser queue and a ZPL test label for the Zebra queue. Standalone setup/test processes use named device and printer choices, with printer choices filtered by device and supported format. Operators do not need to type database identifiers.

## Follow a job

Open **Print jobs** to see the related printer/device, title, format, and lifecycle state. Open the record for diagnostic details. `QUEUED` means waiting for the connector; `SPOOLED` means accepted by Windows, not confirmed physical output. `FAILED` and `OUTCOME_UNKNOWN` require investigation; an uncertain submission is not automatically reprinted.

The Windows companion shows recent jobs and a persistent status footer. **View service logs** opens the service snapshot; the separately labeled companion activity log covers desktop-app diagnostics. Closing the companion hides it to the tray while the service continues.

## Preview acceptance and limits

The live preview was inspected with frontend 1.0.0-RC.37: the six operational printer columns, named device relationship, disabled generic write actions, and dashboard-to-filtered-table navigation were observed. Selecting the synthetic Zebra, opening Print a test label, reviewing and submitting completed with one printer processed. No physical printer was connected to this demo. Native process authorization and multi-printer execution are also covered by Java tests. These are historical preview observations, not evidence for every browser size or deployed printer driver. Current release status is in the [README](../README.md) and [release guide](RELEASES.md).
