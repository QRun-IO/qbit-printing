# Test-document font

`JetBrainsMono-Bold.ttf` is the unmodified static **JetBrains Mono Bold v2.304** font from JetBrains. It is bundled for the built-in PDF and rasterized ZPL test documents, so those paths use the same letterforms without relying on installed computer or printer fonts.

Copyright 2020 The JetBrains Mono Project Authors. The font is licensed under the **SIL Open Font License 1.1**, reproduced in [OFL.txt](OFL.txt); it is not covered by this repository's Apache license. Preserve that license and attribution when redistributing the font.

## Provenance

- [Official font design and character comparisons](https://www.jetbrains.com/lp/mono/)
- [Official tagged font source](https://github.com/JetBrains/JetBrainsMono/blob/v2.304/fonts/ttf/JetBrainsMono-Bold.ttf)
- [Official tagged license](https://github.com/JetBrains/JetBrainsMono/blob/v2.304/OFL.txt)
- Acquired and inspected: 2026-10-30. No modifications or font conversions.
- Font size: 277,828 bytes.
- Font SHA-256: `5590990c82e097397517f275f430af4546e1c45cff408bde4255dad142479dcb`
- License SHA-256: `30f0c136e3c88e422d0791acd97238870f9054a9729bc34cf2ff0d4ed8cac4ad`

## Rendering and readability

Visual inspection of the actual bundled font confirmed a dotted zero versus plain capital O, an angled top and baseline on digit 1, horizontal bars on capital I, and a curved foot on lowercase l. The 5/S, 2/Z, 8/B and 6/G pairs also have visibly different outlines. Inspection included nominal 12-point text rasterized at 203 and 300 dpi; physical printer acceptance remains a separate check. This does not guarantee error-free reading at every size or on damaged labels.

Use ordinary character drawing without programming ligatures for identifiers. Favor at least 12-point text for important warehouse identifiers, generous whitespace, and a larger explicit ambiguous-character proof row. Keep barcode generation separate from font rendering.

The file contains TrueType `glyf` outlines, has no variable-font `fvar` table, and its OS/2 `fsType` is zero. It therefore has the static TrueType structure expected by the project's PDFBox 2.x embedding path. Load the resource with `PDType0Font.load(document, inputStream, true)` to embed a subset. See the [official PDFBox 2.x API](https://pdfbox.apache.org/docs/2.0.13/javadocs/org/apache/pdfbox/pdmodel/font/PDType0Font.html). The resource inspection itself is not a substitute for the generator's PDFBox regression test.

For ZPL, load the same resource through `Font.createFont(Font.TRUETYPE_FONT, inputStream)`, derive the required pixel size from the selected printer dpi, render onto an opaque white background, and encode the monochrome result as document-local graphics. This preserves the distinctive glyphs without installing a font on the printer. Raster previews and physical output still need inspection at each supported resolution.
