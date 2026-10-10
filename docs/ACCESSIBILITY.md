# Accessibility writing assistant

The optional service adds a draggable **W** bubble to a focused text editor while keeping the user's current keyboard. Tap the bubble for corrections, rewrite, tone, Undo, dismiss, a 15-minute pause, and blocking the current app. Model inference uses the app's local `AnalysisSession`; this service contains no network, account, telemetry, clipboard-read, or text-logging code. Clipboard writes happen only through the panel's explicit Copy button.

## Original APK evidence: static analysis

The supplied APK's decompiled sources were inspected at:

- [`pp0.java`](../../oss-grammerly/build/jadx/sources/defpackage/pp0.java): `onCreateInputMethod()` constructs `n6`, an Android accessibility `InputMethod`, on API 33 and later.
- [`n6.java`](../../oss-grammerly/build/jadx/sources/defpackage/n6.java): implements `onStartInput`, `onUpdateSelection`, and `onFinishInput` alongside its service's accessibility event handling.

These observations are from static decompilation, not claims that every original-app interaction was reproduced on a device. The replacement implements the public Android mechanism independently in Kotlin. Original product code, analytics, login, and service endpoints are not included.

Android documents [AccessibilityInputConnection](https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection) and [accessibility InputMethod](https://developer.android.com/reference/android/accessibilityservice/InputMethod). `FLAG_INPUT_METHOD_EDITOR` enables the accessibility input connection, introduced in Android 13/API 33. This is distinct from replacing the selected on-screen keyboard. The configured service must have this flag and window-content access.

## Editor capture and application

`WriterAccessibilityService` prefers `getSurroundingText()` from the accessibility input connection. This supports editors whose text is exposed through the IME protocol, including browser or Compose virtual editors when those apps implement the protocol. A focused editable accessibility node is the fallback, using `ACTION_SET_TEXT` and `ACTION_SET_SELECTION`.

Each captured snapshot records immutable text, its absolute UTF-16 offset, package, input-method editor epoch/field identity, accessibility window/source identity, input route, selection, and revision. The service re-reads the focused editor before applying an edit. A new field, package, text, offset, selection revision, invalid node, or blocked app invalidates the old result. A sheet is disposed when its field changes, even if two editors contain identical text. For nodes, full captured text and node equality are also checked before writing.

For the accessibility input connection, the service selects the exact captured range, re-reads and checks both selection and source text, then calls `commitText`. Android exposes these write calls as `void`, with no atomic compare-and-replace transaction. A successful submission therefore means the command was accepted for dispatch; subsequent editor events confirm the resulting text. A host app that changes text between the final read and the write can still cause a platform-level race. Unsupported editors fail without falling back to simulated typing or clipboard paste.

Undo retains only the last correction and its original text in memory. It requires the same field and the exact corrected range; node undo also checks the full expected field value. Once the correction is confirmed, a later text-change event discards undo. There is no persisted user-text history.

## Bounds and exclusions

- Text/visible/web/number password variations, password accessibility nodes, number-only editor types, blocked apps, this app, System UI, and locked/noninteractive screens are excluded.
- Analysis reads at most 2,048 UTF-16 units before and after the current selection, with an overall 4,096-unit analysis limit. Oversized selected ranges are skipped. Accessibility-node fallback retains at most 50,000 units to safely reconstruct the whole value, while analyzing a maximum 4,096-unit slice near the cursor. Long text is not analyzed in its entirety.
- Focus lookup uses the framework's focus query first, then a scan bounded to 160 nodes, 24 children per node. Events trigger debounced refreshes; there is no continuously running window-polling loop.
- A nonfocusable `TYPE_ACCESSIBILITY_OVERLAY` retains editor focus. Bubble and bounded scrolling sheet positions are clamped to system/cutout insets and the IME window. No draw-over-other-apps permission is needed for this service.
- Pause and application blocking share `Prefs.allowed` with the other app surfaces. Screen-off, interrupted service, finished input, and destruction remove overlays, dispose model sessions, and release text/node references.
- Some apps expose neither a useful input connection nor editable accessibility actions, mark content secure, omit offsets, reject writes, or fail to emit editor events. Universal support for every text field cannot be guaranteed by Android accessibility APIs.

## Runtime verification

This document describes implemented safeguards and static-source findings. Physical-device verification and a complete app build are performed by the integrating task; no emulator was used for this implementation. Device observations must be recorded separately after testing native fields, browser editors, Compose fields, password exclusions, field/app switching during inference, scrolling/IME movement, lock/unlock, and stale-undo rejection.
