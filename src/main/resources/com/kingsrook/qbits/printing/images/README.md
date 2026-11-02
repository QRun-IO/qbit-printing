# Query mascot used in built-in print diagnostics

`query-mascot.png` is the official Query dog artwork, copied byte for byte from
QRun.IO's marketing source. It replaces the former interlocking-Q crown mark in
built-in test documents. Query is identified by the official
[mascot page](https://qrun.io/query), linked from
[dogfooding guidance](https://qrun.io/dogfooding).

- Source repository: [QRun-IO/marketing-website](https://github.com/QRun-IO/marketing-website).
- Asset source revision: `5ff7c1564f4fe28a7ce75436cf7c4a6eeaa4bf3f`.
- Source file: [`assets/Puppy Mark/exports/PNG/qqq-puppy@4x.png`](https://github.com/QRun-IO/marketing-website/blob/5ff7c1564f4fe28a7ce75436cf7c4a6eeaa4bf3f/assets/Puppy%20Mark/exports/PNG/qqq-puppy%404x.png).
- Dimensions: 1326 x 1394 pixels, transparent PNG.
- SHA256: `385a053e15e0a9fc6f8a965257e3917db827594ab46ecf8cdddb847770a25dbb`.
- Verified2026-10-31 against the official page: the two SVG paths in its Query
  illustration match `public/images/qqq-puppy.svg` in the same repository.

The bundled PNG is unmodified. At document generation, transparent pixels are
composited onto white and the image is centered in a square white canvas. This
preserves the original aspect ratio inside existing PDF and thermal-label layout
boxes. The resulting document artwork is monochrome for warehouse printing;
there is no AI redraw, added outline, rotation or printer-persistent download.
Existing diagnostic text, font, media profiles, barcode data and quiet zones are
unchanged. The one shared generator supplies all native QQQ and device test-print
entrypoints, including Windows companion requests.

This resource brands QQQ's built-in diagnostic documents only. It does not
replace a customer's submitted document artwork, Windows application/tray icons
or future customer-specific branding configuration. Query remains QRun.IO/QQQ
branding; this provenance is not a separate trademark permission grant.
