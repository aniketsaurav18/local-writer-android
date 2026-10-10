# Build and device record — 2026-10-10

The original Kotlin implementation was built with Kotlin 2.2.20, Android SDK/build-tools 36.0.0, NDK 27.1.12297006, and the pinned CPU-only llama.cpp runtime. Both local models are bundled in the signed ARM64 APK. Build-time checks validate signatures, manifest permissions, model asset hashes and native-library ZIP alignment.

Before the owner requested testing stop, host checks passed with the real ONNX grammar model for subject/verb agreement, contractions, duplicate-word removal, prepositions, whitespace preservation, long documents, cancellation, UTF-16 boundaries, caret mapping and private-field exclusions. USB ADB installation on the connected OnePlus CPH2707 (Android 16/API 36) succeeded. Opening the independent `dev.localwriter` package displayed its Home and Editor screens without the original app's Play Store redirect or login flow.

That initial editor check exposed Android's rejection of Java's `UNICODE_CHARACTER_CLASS` regex flag. The implementation now uses explicit Unicode word classes and removes the unsupported flag from the BPE regex. Host checks after that source fix passed before the stop request. The corrected final build has **not** been exercised on the phone. Runtime testing stopped at the owner's request; no Android emulator was used.

The owner will verify corrections, local rewrites/tone, keyboard behavior, accessibility permission setup and cross-app overlays. Accessibility has not been enabled automatically, and the selected system keyboard has not been changed. Universal support for secure fields or editors that refuse Android accessibility/IME access is not claimed.

## Overlay follow-up

At the owner's request, a read-only ADB screenshot showed the X draft with no red marks and only Android's square accessibility shortcut. Read-only service state confirmed the writing service was disabled. The owner subsequently enabled it. After the updated APK was built and installed, another requested ADB screenshot showed red underlines on the draft and the round W 3 correction bubble, alongside the optional square Android shortcut. The screenshot is a visual observation, not evidence that accepting corrections or keeping the sheet open worked.

The owner reported that tapping W 3 briefly opened the sheet and then closed it. Source review identified the sheet's own window-state event being treated as a switch away from the editor. The lifecycle fix is included in version 0.1.1. The owner requested no further agent-driven runtime testing; the updated sheet behavior remains for the owner to verify. New pure-code regression assertions for spelling and visible insertion ranges were added but not executed during this follow-up.

Version 0.1.2 replaces the round W/count badge with an app-controlled square LW widget matching the owner's preferred shortcut style. Idle field refresh now clears the widget when the keyboard is hidden, with an exception for live physical-keyboard editors. An already-open correction sheet retains the previous lifecycle safeguards. Android controls its separate optional shortcut; the owner must disable only that shortcut option and keep the service enabled to eliminate the second button. No runtime or host tests were run for this update, and no phone settings were changed automatically.

Version 0.1.3 removes `flagRequestAccessibilityButton` from service metadata and removes its controller callback. The prior version retained that flag, which Android Settings uses to choose a shortcut-oriented setup with a hidden service toggle for modern target SDKs. Setup now asks only for writing-assistant access. A saved Android shortcut may persist after an upgrade; the owner controls its removal. No system settings were changed and no runtime or host tests were run.

Version 0.1.4: one requested read-only ADB screenshot showed the 48dp square widget occupying the right side of an X draft. Source-guided changes replace it with a 30dp green circle, centered live count, loading/clear states, an immediately seeded correction sheet, and the supplied original revision layout measurements. The screenshot is saved under build/evidence/widget-before-014.png (ignored because it contains private draft text). No runtime or host tests were run; accepting/dismissing and rendering remain for the owner to verify.

Version 0.1.5 separates compact inline edits from expanded revision mode, following the owner's compact-first choice and additional supplied-source inspection. It uses the original open Inter font families, a small directly applicable edit pill with trash action, counted expansion control, full-width sheet above the IME, fixed header/icon toolbar, and a scrolling list with one contextual diff per card. Known suggestions seed both modes; the inline counter remains draggable. The final source was rebuilt successfully, including build-time signature, package/permission, model-hash and native-alignment checks. No host tests, emulator, runtime navigation or automatic phone-settings changes were performed. The owner will verify the new interaction and appearance.

The signed version 0.1.5 update was installed successfully using `adb install --no-incremental -r` on the connected OnePlus CPH2707. The agent did not launch or interact with the app after installation.

## Grammar model follow-up (0.1.6)

At the owner's request, a read-only ADB screenshot captured an active X draft and an inline panel reporting No suggestions. It is saved privately under ignored `build/evidence/missed-errors-015.png`. No device navigation, typing, taps or settings changes were performed. A proposed phrase-specific warning rule was rejected by the owner and removed before installation.

Primary upstream inspection then identified general model-input/decoder mismatches: leading-space raw-text BPE instead of bare whitespace tokens, separate punctuation/contraction tokens, an unsupported global 0.7 confidence floor, and suppression of adjacent identical labels. Version 0.1.6 fixes those paths, keeps four iterative rounds, and applies predictions against original token indexes. [Model documentation](GRAMMAR_MODEL.md) records the pinned source references. No model inference, host tests, emulator or runtime tests were run. Detection quality and the previously missed text remain for the owner to verify.

The revised version 0.1.6 APK built successfully with signature, package/permission, bundled-model hash and alignment checks, then installed successfully on the connected OnePlus CPH2707 using `adb install --no-incremental -r`. The rejected rule-based build was never installed. No app launch or phone interaction followed installation.

## Offline architecture update (0.2.0, 2026-10-11)

This update replaces the default continuous-checking path with native Harper 2.12.0 and the contextual path with pinned Unbabel GEC T5-small Q8_0 GGUF. The latter is 82,843,360 bytes; the ARM64 Harper library is 12,505,248 bytes. The former ONNX tagger remains an optional Legacy mode, and Qwen rewriting/tone remain explicit actions. APK storage includes all three neural assets; this is not a lightweight distribution.

Kotlin and both native adapters compiled successfully. Build-only validation checks the APK signature, independent package, absence of network/login/telemetry manifest integrations, service/IME declarations, byte-for-byte model asset hashes, the pinned contextual asset digest, required native libraries and 16 KiB ZIP alignment. Harper's ELF LOAD alignment and exported JNI entry point were inspected statically. Full third-party license notices are bundled.

Source review identified and corrected partial-result flicker, duplicate overlapping engine findings, neural changes to personal dictionary names, selection-only result invalidation, optimistic updates before asynchronous external commits, first-use cancellation races, and heavy-model residency. The correction badge and both external panel modes use a shared store; unchanged-sentence findings/dismissals survive suitable edits, warnings have no Accept action, alternatives are reviewable, and bulk acceptance is restricted to compatible corrections with one replacement. These are implementation descriptions, not observed runtime results.

At the owner's request, no host tests, grammar/model inference, emulator, phone navigation or settings changes were performed for this update. The phone is disconnected, so 0.2.0 has not been installed. The owner will evaluate actual correction quality, latency, memory and external-field behavior after installation.

Final signed APK: **754,289,664 bytes** (about 719 MiB), version code **8**, version name **0.2.0**. Release build completed with the checks above; SHA-256 is recorded in `dist/SHA256SUMS`.
