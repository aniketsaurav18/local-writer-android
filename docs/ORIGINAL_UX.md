# Original UX reference and independent implementation

The supplied Grammarly 2.118.100055 APK was already decompiled under the sibling `oss-grammerly/build/jadx/sources` directory. This investigation reused those artifacts; the app implementation includes none of the proprietary classes or assets.

## Observed from static sources

- `defpackage/c0.java:858`, `c34.java:60`: an app-owned draggable logo overlay, separate from Android's accessibility shortcut.
- `defpackage/f54.java:160`, `oo4`, `uo4`: logo click opens revision mode.
- `defpackage/ag4.java:49`, `b34.java:429`, `hhc.java:817`: revision content attached in an overlay bottom sheet.
- `defpackage/wm8.java:298`, `im8.java:329`, `im8.java:927`: a vertically scrolling list of expandable correction cards with Accept/Dismiss actions. `wm8.java:216` scrolls to the selected card.
- `defpackage/yx8.java:100`, `nw0.java:513`, `az0.java:76`: suggestion ranges drawn with local text-layout geometry, and underline taps selecting their suggestion. The traced path belongs to the app's scratchpad (`dy8.java:298`).

No client `refreshWithExtraData` or `getAvailableExtraData` use was found in the decompiled source. Character-location strings occurred in bundled Compose accessibility providers. This bounded static search does not establish that the original app injects highlights into external fields. Original live UI behavior was not observed because its modified installation previously redirected to Play Store.

## Resulting Local Writer behavior

The compact circle opens a correction sheet immediately using already-checked suggestions when available; pending checks continue through the panel. Cards expand/collapse, show marked context and old/new text, and offer Accept/Dismiss. A marked text preview selects and scrolls to the matching card. The app editor marks insertion errors on a visible word instead of whitespace.

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
