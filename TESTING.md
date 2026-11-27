# Testing – what is automated and what is not

**Nothing below has been executed yet.** The automated tests are written and desk-checked but need the first CI run;
the device checklist needs a person with a phone. Do not treat either as "passed" until you have seen it pass.

## A. Automated unit tests (run by GitHub Actions: step "Unit tests", reports in artifact `test-reports`)
They run on a plain JVM, without a device. They test logic, **not** how text looks on screen.

| File | Covers |
|---|---|
| `DocxParserTest` | runs/formatting, Bengali round-trip, run merging (conjunct safety), complex-script size/bold, styles, lists, sections, images extracted to disk, friendly errors |
| `FidelityTest` | explicit/auto/theme colours, shade, theme fonts (incl. Bengali), highlight, run shading, letter spacing, paragraph shading/borders/indents/exact line height, stable paragraph ids, table styles (header fill, banding, inside borders), direct cell overrides, vertical merge, anchored images |
| `PaginatorTest` | page flow, margins, page breaks, space-before rules, widow/orphan, keep-with-next, images, tables (rows, spans, scaling, centre, vertical align), shading/borders layers, anchored images, sections, cancellation, progress, end-to-end Bengali+English document, 3000-paragraph speed |
| `FontAndTextTest` | font file matching, bold/italic file pick, Bengali/English substitution decisions, script segmentation |
| `LogicTest` | themes + contrast, **Old Lace value**, colour policy, **zoom anchoring maths**, selection (word, paragraph, rectangles, copy text), page geometry, highlight storage/persistence, dictionary text/stemming/import parsing, PDF word/rect/copy logic |

**Not covered by automated tests (cannot be, without a device):** real Bengali shaping on screen, StaticLayout line breaking,
touch gestures, animations, PDFBox extraction on real PDFs, Google Translate launching, text-to-speech, system bars.

## B. Real-device checklist (your 17 scenarios)
Mark each as pass / fail / notes. Use files you know well; compare against Word or a PDF viewer.

| # | Scenario | What to check |
|---|---|---|
| 1 | Bengali DOCX with SolaimanLipi | import the font (Settings), open file: conjuncts (ক্ষ জ্ঞ শ্র), vowel signs, no overlapping lines |
| 2 | Bengali DOCX with Tiro Bangla | same; then switch "Override document fonts" on/off |
| 3 | Mixed Bengali + English | each script in its own font/size, no clipped marks |
| 4 | Multiple font colours | colours equal Word's (red stays red, blue stays blue); automatic text follows theme |
| 5 | Tables and images | header-row colours, banding, borders, cell shading, images not clipped |
| 6 | Complex paragraphs | indents, spacing, line spacing, lists, shaded/bordered paragraphs |
| 7 | Large multi-page DOCX | first pages appear quickly; scroll smooth; no crash |
| 8 | PDF selection and copy | long-press a word, drag handles, Copy, paste elsewhere = same text |
| 9 | DOCX selection and copy | same in a DOCX, include a Bengali word with a conjunct |
| 10 | Dictionary | with no list: honest message + links; after importing a small TSV: meaning, alternatives, pronunciation |
| 11 | Google Translate | opens with the text, auto source, Bengali target (internet on) |
| 12 | Highlights | all 5 colours; close and reopen the document: still there; change colour; delete |
| 13 | Continuous-scroll zoom | pinch at the top, middle, bottom: the spot between your fingers stays put; zooming out does not drift to a corner |
| 14 | Header/footer animation | tap: smooth fade/slide, page does not jump or flicker |
| 15 | All 9 preset themes | readable; Old Lace on home, header, footer |
| 16 | Custom colours | edit each of the 4 colours via sliders and HEX; save; restart app: remembered; reset works |
| 17 | Light and dark | Dark Grey and Pure Black: DOCX text readable, explicit dark colours lifted only where unreadable |

When something fails, send: the scenario number, a screenshot, and (if possible) the DOCX/PDF.
