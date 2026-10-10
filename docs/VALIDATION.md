# Build and device record — 2026-10-10

The original Kotlin implementation was built with Kotlin 2.2.20, Android SDK/build-tools 36.0.0, NDK 27.1.12297006, and the pinned CPU-only llama.cpp runtime. Both local models are bundled in the signed ARM64 APK. Build-time checks validate signatures, manifest permissions, model asset hashes and native-library ZIP alignment.

Before the owner requested testing stop, host checks passed with the real ONNX grammar model for subject/verb agreement, contractions, duplicate-word removal, prepositions, whitespace preservation, long documents, cancellation, UTF-16 boundaries, caret mapping and private-field exclusions. USB ADB installation on the connected OnePlus CPH2707 (Android 16/API 36) succeeded. Opening the independent `dev.localwriter` package displayed its Home and Editor screens without the original app's Play Store redirect or login flow.

That initial editor check exposed Android's rejection of Java's `UNICODE_CHARACTER_CLASS` regex flag. The implementation now uses explicit Unicode word classes and removes the unsupported flag from the BPE regex. Host checks after that source fix passed before the stop request. The corrected final build has **not** been exercised on the phone. Runtime testing stopped at the owner's request; no Android emulator was used.

The owner will verify corrections, local rewrites/tone, keyboard behavior, accessibility permission setup and cross-app overlays. Accessibility has not been enabled automatically, and the selected system keyboard has not been changed. Universal support for secure fields or editors that refuse Android accessibility/IME access is not claimed.
