# PageFlow – feature status (v0.3.0)

PageFlow is a **document reader** (DOCX and PDF). It has no editing mode and never modifies your files.

**Honesty note.** The v0.1 debug build was compiled by GitHub Actions and installed on a phone. Everything added in
v0.3.0 has been written but **not compiled and not run** by the author. "Implemented" means "the code exists",
not "verified on a device". See TESTING.md for what is automated and what must be checked by hand.

## Why the custom DOCX renderer was kept (and what the alternatives would cost)
* LibreOffice (headless DOCX→PDF) is the only engine with near-Word fidelity, but it has no maintained Android build:
  compiling it needs a multi-hour native toolchain, and the result is 100 MB+. Not feasible with free CI.
* Apache POI / docx4j give a parser but no page layout, and need `java.awt`, which Android lacks.
* Commercial SDKs (Aspose, Syncfusion) render well but are paid and closed-source.
* So v0.3.0 improves the own renderer where the visible errors were (colours, table styles, shading, borders, spacing).
  It will still **not** match Word page-for-page.

## Implemented in v0.3.0 (untested on device)
| Area | What exists |
|---|---|
| Colours | Original text colours kept. Only *automatic* text takes the theme's text colour; an explicit colour is replaced only if it is nearly invisible (contrast < 2) on its background. Theme colours (`themeColor`, shade/tint) and theme fonts (incl. Bengali script font) are resolved. Run shading, highlight, paragraph shading, table-cell fills are drawn |
| Tables | Table styles with conditional parts (header row, banded rows/columns, first/last column, corner cells), `tblLook`, per-side cell borders with colour and width, inside/outside borders, cell margins, vertical alignment, centred/right tables, indent, row min-height, grid spans, vertical merges (border hidden between merged cells), images inside cells |
| Paragraphs | Alignment (incl. justify), before/after spacing, line spacing (auto / exact / at least), left/right/first-line/hanging indents, tab stops, bullets and numbers, paragraph borders and shading, keep-with-next, widow/orphan control, contextual spacing, page-break-before |
| Characters | Bold, italic, underline, strike, super/subscript, all-caps, letter spacing, colour, highlight, run shading; Word's separate Bengali (complex-script) size/bold/italic |
| Pages | Page size and margins per section (portrait/landscape), continuous sections, explicit page breaks, space-before rules at page tops |
| Images | Inline images; anchored images behind/in front of text placed at their page/margin/paragraph position; square/tight/top-bottom anchored images placed in the text flow (text does **not** wrap beside them) |
| Reading | Single-page mode and continuous scrolling for both DOCX and PDF; page slider; go to page; search in DOCX; last position remembered |
| Zoom / pan | One shared engine: pinch zooms around the midpoint of your fingers, one-finger pan with fling, double-tap zoom, +/− buttons; PDF re-renders the visible area for sharp text at any zoom |
| Controls | Tap toggles header/footer; fade + slide animation; page does not move (edge-to-edge window, controls float over the page) |
| Selection | Long-press selects a word; drag handles; "¶" selects the paragraph (DOCX); copy; works in DOCX (character offsets from the layout) and PDF (character boxes from PDFBox-Android) |
| Toolbar | Copy · Dictionary · Translate · Highlight (5 colours) |
| Dictionary | Offline SQLite look-up from a word list **you import** (TSV/CSV); word-form fallback (plural/-ing/-ed, common Bengali suffixes); alternative meanings; pronunciation via the phone's text-to-speech; Wiktionary link as online fallback |
| Translate | Opens Google Translate with the selected text, source auto-detected, target Bengali (needs internet) |
| Highlights | Yellow, light green, light blue, pink, orange; saved privately per document; restored on reopen; tap a highlight to change colour or delete; DOCX highlights are tied to paragraph + character offsets so they survive font-size changes |
| Themes | Classic White, Old Lace (#FDF5E6), Warm Cream, Soft Ivory, Sepia, Soft Sage, Mist Blue, Dark Grey, Pure Black; custom theme editor (page, text, header, footer; colour sliders, HEX, RGB read-out, live preview, contrast warnings, save, reset); choice remembered; Old Lace is the default and colours the home screen, header and footer |
| Fonts | Import TTF/OTF, preview, reading font, override switch, size, line spacing, bold/italic sibling-file matching |
| Files | Persistent access for recents; private copy for files opened from other apps; access check before opening |
| Build | GitHub Actions (Node-24 action versions), unit tests, persistent signing from Secrets, debug fallback |

## Known limitations – please read
* **Dictionary data is not included.** I cannot verify the licence or accuracy of a bundled English–Bengali dataset and
  will not invent meanings. Without an imported list the popup says so and offers Wiktionary / Google Translate.
* **Google Translate and Wiktionary need internet.** There is no in-app translation popup (no supported offline or free API).
* **PDF text selection depends on the PDF.** Scanned PDFs have no text. PDFs built with legacy (non-Unicode) Bengali fonts can
  extract as wrong characters. Extraction runs per page when you long-press, so the first selection on a page can take a moment.
* **PDF coordinates are mapped from PDFBox page coordinates to the rendered page.** PDFs with unusual crop boxes or rotation may
  show the selection offset; this is exactly the kind of thing that needs a real-device check.
* **Selection in DOCX stays within one page**; tables' text can be selected, shapes and text boxes cannot.
* **Not rendered:** headers and footers, footnotes/endnotes, text boxes and shapes, columns, text wrapping *beside* floating
  images, WordArt, equations, charts, SmartArt, dashed/double border styles (drawn solid), table header-row repetition,
  splitting of a table row across pages (a row taller than a page is clipped), right-to-left text, tracked-change marks.
* **PDF dark themes:** PDF pages keep their own (white) page; the theme colours the surroundings and the controls.
* **Memory:** the parsed DOCX and its text layouts stay in memory (pages are only *drawn* lazily). Huge documents (thousands
  of pages) may be slow or fail on low-end phones. PDF pages are rendered on demand and cached (about 48 MB).
* **APK size** grew by roughly 6–9 MB because of the PDF text library.

## Planned (not built)
Bookmarks list, search inside PDFs, headers/footers, text wrapping beside images, in-app translation, bundled or
downloadable dictionary pack (needs a verified licence), cross-page selection, export of highlights.
