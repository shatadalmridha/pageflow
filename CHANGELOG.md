# Changelog

## 0.3.1
* **Build fix:** a helper class in `Selection.kt` was named `R`, which clashes with Android's generated resource class
  `com.pageflow.R`. Renamed to `Rc`. (This is the only compile problem I could identify without the build log.)
* **Workflow:** when Gradle fails, the Kotlin compiler errors and failed tests are now written to the run's *annotations*
  and *job summary* (readable without signing in) and the full logs are uploaded as artifact `gradle-logs`.

## 0.3.0
**Direction:** reader only. No editing was ever shipped; the editing requirement is removed from the roadmap.

**DOCX rendering** — see FEATURES.md for the full list. The main cause of "wrong colours / messy design" in 0.2 was:
(1) a colour filter that replaced light colours with the text colour, (2) table styles ignored (no header/band colours,
no borders unless set per cell), (3) no paragraph shading/borders, (4) theme colours and theme fonts ignored.
All four are fixed. Also new: exact/at-least line height, letter spacing, run shading, anchored images, vertical
alignment in cells, vertical merges, right indents.

**Zoom/pan** — new shared engine (`Viewport.kt`): anchored at the finger midpoint, fling, clamped; continuous mode no longer
scales from the top-left corner. PDF and DOCX behave the same.

**Controls** — edge-to-edge window so hiding system bars no longer resizes the page (the cause of the flicker);
fade + slide animation; only the bar composables recompose.

**New:** text selection (DOCX + PDF), copy, dictionary, translate, highlights, 9 themes + custom editor, Old Lace.

**Build:** `com.tom-roush:pdfbox-android:2.0.27.0` added (PDF text). Actions: checkout v5, setup-java v5,
setup-android v4, upload-artifact v6, setup-gradle v5.

**Tests:** 149 unit tests in 6 files (written, not yet run).
