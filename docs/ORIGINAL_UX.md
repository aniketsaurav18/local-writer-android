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

The round W bubble opens a correction sheet immediately, before local inference finishes. Cards expand/collapse, show marked context and old/new text, and offer Accept/Dismiss. A marked text preview selects and scrolls to the matching card. The app editor marks insertion errors on a visible word instead of whitespace.

As an enhancement, external underlines use exact Android character rectangles when the host exposes them. Unsupported geometry falls back to the marked sheet preview; it is not estimated from screenshots or guessed font metrics. No field is changed merely to draw underlines.

The initial ADB screenshot showed only Android's square shortcut, and read-only system queries confirmed the service was disabled. Setup guidance now distinguishes the service toggle from its optional shortcut, and the service handles shortcut clicks after enablement. The owner handles permission/setup changes and enabled the service during this follow-up. Emulator testing remains excluded.

## Sheet lifetime

A focused follow-up found explicit sheet ownership in the original: `qo4.java:62` identifies a shown sheet with `uo4`; `n6.java:35` returns from input-finish without resetting user text while that sheet is shown. Start-input (`n6.java:47`) and selection updates (`n6.java:79`) also avoid refreshing the frozen text during sheet ownership. `y6.java:29` ignores selected package events and preserves the previous field when an event has no source. `mg.java:131` routes window events using sheet state; explicit Back/session-stop uses `ro4`/`to4`. The decompiler warns about portions of `mg`, so its reconstructed individual conditions are not copied literally.

Local Writer previously closed the sheet on any window-state event whose package differed from the external text field, including the sheet's own event. It now preserves its captured target through its own overlay/input transitions and bounded temporary missing-window reads. Real field/app navigation, private/locked screens and explicit dismiss still invalidate the target. Every accepted correction re-reads the actual field and validates its identity and source text.
