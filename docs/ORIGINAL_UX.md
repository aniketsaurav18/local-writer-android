# Original UX reference and independent implementation

The supplied Grammarly 2.118.100055 APK was already decompiled under the sibling `oss-grammerly/build/jadx/sources` directory. This investigation reused those artifacts; the app implementation includes none of the proprietary classes or assets. The open-source Inter font files used for overlay typography are separately attributed under their SIL OFL licenses.

## Observed from static sources

- `defpackage/c0.java:858`, `c34.java:60`: an app-owned draggable logo overlay, separate from Android's accessibility shortcut.
- `defpackage/f54.java:160`, `oo4`, `uo4`: logo click opens revision mode.
- `defpackage/ag4.java:49`, `b34.java:429`, `hhc.java:817`: revision content attached in an overlay bottom sheet.
- `defpackage/wm8.java:298`, `im8.java:329`, `im8.java:927`: a vertically scrolling list of expandable correction cards with Accept/Dismiss actions. `wm8.java:216` scrolls to the selected card.
- `defpackage/yx8.java:100`, `nw0.java:513`, `az0.java:76`: suggestion ranges drawn with local text-layout geometry, and underline taps selecting their suggestion. The traced path belongs to the app's scratchpad (`dy8.java:298`).

No client `refreshWithExtraData` or `getAvailableExtraData` use was found in the decompiled source. Character-location strings occurred in bundled Compose accessibility providers. This bounded static search does not establish that the original app injects highlights into external fields. Original live UI behavior was not observed because its modified installation previously redirected to Play Store.

## Resulting Local Writer behavior

A numbered circle opens the scrollable correction sheet directly using already-checked suggestions; pending checks continue through the panel. A circle with no counted suggestions opens a compact status pill. Collapsing the sheet shows the inline edit pill; tapping its counter reopens the list and tapping a short actionable correction applies it, while the trash icon ignores the suggestion. Expanded cards show a single contextual edit and offer Accept/Dismiss. A marked text preview is available in options. The app editor marks insertion errors on a visible word instead of whitespace.

As an enhancement, external underlines use exact Android character rectangles when the host exposes them. Unsupported geometry falls back to the marked sheet preview; it is not estimated from screenshots or guessed font metrics. No field is changed merely to draw underlines.

The initial ADB screenshot showed only Android's square shortcut, and read-only system queries confirmed the service was disabled. Version 0.1.3 removed the accessibility-button request and shortcut callbacks; setup requests only service access. The owner handles permission/setup changes and enabled the service during this follow-up. Emulator testing remains excluded.

## Sheet lifetime

A focused follow-up found explicit sheet ownership in the original: `qo4.java:62` identifies a shown sheet with `uo4`; `n6.java:35` returns from input-finish without resetting user text while that sheet is shown. Start-input (`n6.java:47`) and selection updates (`n6.java:79`) also avoid refreshing the frozen text during sheet ownership. `y6.java:29` ignores selected package events and preserves the previous field when an event has no source. `mg.java:131` routes window events using sheet state; explicit Back/session-stop uses `ro4`/`to4`. The decompiler warns about portions of `mg`, so its reconstructed individual conditions are not copied literally.

Local Writer previously closed the sheet on any window-state event whose package differed from the external text field, including the sheet's own event. It now preserves its captured target through its own overlay/input transitions and bounded temporary missing-window reads. Real field/app navigation, private/locked screens and explicit dismiss still invalidate the target. Every accepted correction re-reads the actual field and validates its identity and source text.

## Compact badge and revision layout (0.1.4)

Static references: default SMALL is 30dp (`AppearanceAppData.java:51`, `c64.java:35`), centered 14sp count capped at99+ (`m17.java:1111`); zero uses a logo and loading uses a spinner (`pj.java:168`, `pj.java:235`). The palette resolves to green #027E6F and white (`wt7.java:96`). Badge updates follow the shared suggestion list (`s34.java:112`); clicking opens revision immediately (`f54.java:160`) and that pane observes the same list (`gn8.java:65`, `em8.java:36`).

The revision shell has 28dp top corners (`yi1.java:1768`), a32×4dp handle with 8dp top/12dp bottom spacing (`so.java:3507`), a “Review suggestions” header with 12dp horizontal padding (`wm8.java:26`), list side padding 8dp and card side padding 12dp. Expanded cards show a 12dp category icon with 4dp label gap, context, and right-aligned outlined Dismiss/filled Accept pills with 8dp gap (`im8.java:142`, `im8.java:875`, `im8.java:940`, `t21.java:9`). Local Writer implements these measurements and structure in its own code. Full marked text and bulk accept are available in options.

The original also has a configurable 15dp inactive appearance at 80% alpha (`i64.java:13`, `u64.java:25`) with several state-dependent transitions. The available evidence does not establish one generic inactivity timeout; Local Writer keeps the active 30dp size while typing instead of inventing an equivalent timer. Exact pixel equality and the original rendered UI remain unverified; no original runtime navigation was performed.

The app hides its badge while its own sheet is open and restores it on close, preventing interference with Accept. The original bounded source has an intersection-based blank-badge rule (`m34.java:100`, `h44.java:61`, `p34.java:200`), but no explicit revision-sheet hide/reposition rule; the app behavior is an independent adaptation.

## Separate compact and expanded surfaces (0.1.5)

The owner chose compact first, then expansion to the list. Further inspection distinguishes the original inline surface (`yi1.m`, `yi1.l`, `ix1.j`) from revision mode. Its mini title is 10sp, short styled edit is 13sp Inter Regular, title/edit gap is 2dp, and pill corners are 12dp with 10dp left/8dp right/2dp vertical padding. The ignore icon has a 37dp target (`yi1.k`, `ix1.g`). Applying the short edit is direct (`mp.java:98`); long or special edits fall back to Review suggestions. Local Writer uses this compact arrangement next to its counted badge and supports dragging the inline badge.

Revision uses a 16sp Inter Tight heading with 24dp line height (`dna.d`, `hoa`, `in3`), 11sp Inter category labels (`dna.o`), and 16sp Inter context with 24dp line height (`dna.k`, `im8.f`). The bottom toolbar places the product icon left and an AI icon right with flexible space between them (`xgc.o`); it does not contain three large Corrections/Rewrite/Tone tabs. Local Writer now keeps its header and toolbar fixed around the scrolling list and removes the duplicate context-plus-diff presentation.

The original phone overlay is full width and bottom aligned (`ag4.java:49`, `yi1.p`). A previously noted 650dp value is a default **height**, not a maximum width. Local Writer's expanded sheet fills the space above the keyboard, within system/cutout insets. Its inline surface remains small and anchored at the badge. The local model exposes edits rather than the original server's explanation/category metadata, so issue titles are inferred from each transformation. Exact rendered equality remains unverified; no agent-driven phone interaction or runtime tests were performed.

## Live compact reference (2026-10-11)

An owner-requested ADB capture now shows the original running on the left and Local Writer on the right. In dark mode the original has a single dark outlined pill, approximately 38dp high, with a 30dp coral count badge and a squared lower-left corner, a muted category above the edit, and a trailing trash icon. Local Writer previously opened a separate white rectangle between a detached trash icon and green circle.

The compact surface now uses the observed unified outline and badge geometry, system-aware colors, and content-based width with a 194dp minimum. The count stays on the left even when the window is anchored at the right screen edge. Short edits show the recommended replacement directly, including when alternatives exist; tapping the correction text applies the correction and tapping the count opens the full list. Long edits retain the review action. The numbered launcher now opens the correction sheet directly; its clear/checking state with no counted suggestions opens the compact status pill. The earlier compact-first flow described above is superseded by this interaction. Local Writer keeps its own artwork and local-model findings.

Additional owner screenshots establish the expanded and rewrite layouts. New corrections automatically show the compact pill. The sheet covers the external keyboard area at its measured height, with a 280dp fallback. It keeps the keyboard and host editor active so the input bar retains its position. Upward handle drags resize it; upward scrolling expands it to the content height, capped at the usable screen. Downward handle drags move the menu with the finger and dismiss it after a sufficient drag; short or cancelled drags return it to place. A toolbar × button also slides the menu closed. The toolbar has one × close action. The compact pill is draggable across its number, suggestion text, and dismiss icon, while taps retain their actions. Its initial position is 32dp higher. The top toolbar switches between correction rows and rewriting. Corrections show struck-through source and highlighted replacement text; explanations and alternatives remain under a details control. Rewriting shows a grammar-corrected preview from available findings, horizontal model actions, a reviewed replacement action, and a bottom custom prompt field. Prompt input retains the external editor target and every replacement still validates its current source.
