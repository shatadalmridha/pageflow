# PageFlow – feature status

**Honesty note:** none of this code has been compiled or run yet. "Implemented" means the code
exists in the project; it has NOT been verified on a device. The unit tests are written but have
not been executed either. The first GitHub Actions run is the first real check.

## Implemented (code written, untested on device)
| Area | What exists |
|---|---|
| DOCX reading | Own parser + page layout engine, page-by-page view, page numbers, paper-colour pages, page margins and page sizes per section (portrait/landscape), explicit page breaks, page-break-before, widow/orphan control, keep-with-next, contextual spacing, space-before rules at page top |
| Formatting | Bold, italic, underline, strike, super/subscript, caps, highlight, colour, alignment (incl. justify), indents, hanging indents, line spacing, tab stops, bullet and numbered lists, styles with inheritance |
| Bengali | Script-aware font choice per segment, Word's complex-script size/bold/italic (szCs/bCs/iCs), run merging so conjuncts are not split, fallback-font line height |
| Tables | Grid widths, column spans, shading, borders, cell margins, row min height, indent, images inside cells; rows move to the next page whole |
| Images | Inline images (also floating ones, placed inline), extracted to disk and decoded lazily at the needed size |
| Fonts | Import TTF/OTF (header-validated), preview, reading font, override switch, size and line spacing, reset, bold/italic sibling-file matching |
| PDF | PdfRenderer; single-page and continuous modes; pinch zoom and pan with sharp re-render of the visible area; double-tap zoom; fit width / fit page; slider, prev/next, go-to-page; last position |
| App | Home, recents (with access check and private-copy fallback), search in DOCX, themes (system/light/dark/sepia/cream/black), full-screen reading, settings, storage clearing |
| Build | GitHub Actions, unit tests, persistent signing from Secrets, debug fallback |

## Known limitations (by design of the approach)
* Page breaks will not match Microsoft Word exactly (different layout engine).
* Headers/footers, footnotes, text boxes, shapes, columns, floating-image wrapping, theme fonts
  (`asciiTheme`), vertically merged cells, repeated table header rows: **not rendered**.
* A table row taller than one page is clipped, not split.
* The whole parsed document and its text layouts stay in memory (pages are only *drawn* lazily);
  very large documents (thousands of pages) may be slow or run out of memory on low-end phones.
* Bengali shaping quality depends on the Android version's text engine and the fonts installed.

## Planned (NOT built)
* DOCX editing, Save As (never overwriting the original)
* Bookmarks list, search inside PDFs, text selection
* Headers/footers, footnotes, repeated table headers, split table rows
* Export to PDF, reading background/text colour customisation, more settings pages
