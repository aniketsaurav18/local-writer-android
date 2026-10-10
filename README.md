# Local Writer

An independently implemented Kotlin Android writing assistant with bundled offline grammar and rewrite models. No account, telemetry SDK, runtime downloads, or internet permission. Package: `dev.localwriter`. Android 13+ on ARM64.

The app provides an editor with correction underlines and suggestion cards, a draggable assistant over other apps, an optional writing keyboard, accept/dismiss/accept-all, Undo, personal dictionary, app exclusions, pause/resume, writing goals, American/British spelling preferences, selected-text rewrites, tone analysis, share-in and Android's selected-text processing action. Rewrites include concise, professional, friendly, expand, improve and translation into English. A small bundled English frequency dictionary adds conservative spelling suggestions independently of the grammar model.

This is original source code. The supplied APK was used to understand the Android accessibility approach; its proprietary classes and assets are not included. Cloud-only quality and behavior cannot be reproduced one-to-one by these smaller local models. Custom editors that omit usable Android accessibility and input-connection interfaces cannot be edited reliably. Passwords are deliberately excluded. See [accessibility implementation and limits](docs/ACCESSIBILITY.md).

## Build

Use Python 3.12+, JDK 17+, CMake, Android SDK platform/build-tools 36.0.0 and NDK 27.1.12297006. `ANDROID_SDK_ROOT` defaults to `~/Android/Sdk`. The SDK command-line build avoids Gradle daemons and uses two native compilation threads and a 640 MiB Kotlin compiler heap. It never starts an emulator.

Inputs are already prepared in this workspace. For a fresh checkout:

```sh
python3 tools/bootstrap.py --host-tests
python3 tools/build.py
```

`bootstrap.py` downloads hash-checked models and Maven dependencies plus the pinned llama.cpp source. To reuse the supplied files instead of downloading them, add `--inputs /path/to/oss-grammerly`. The build downloads the standalone Kotlin 2.2.20 compiler on its first run. These are development downloads; the installed app performs all inference in-process. Large model/vendor/build files are intentionally excluded from Git; pinned versions and SHA-256 hashes are in `dependencies.lock.json`.

Output: `dist/local-writer-arm64.apk` and `dist/SHA256SUMS`. Preserve the private development signing key in `build/signing/local-writer.p12` for compatible updates. It is excluded from Git. The APK is approximately 624 MiB; unpacked native libraries and the first-use private model copies need additional device storage.

## Install and enable

```sh
adb install --no-incremental -r dist/local-writer-arm64.apk
adb shell am start -n dev.localwriter/.MainActivity
```

Open **Local Writer → Set up writing assistant**, turn on **Use Local Writer assistant** in Android accessibility settings, and allow access. The app does not request Android's accessibility button or handle shortcut clicks. Android may still offer its generic shortcut setting; an existing shortcut from earlier builds may need to be disabled once in system settings. The app shows one draggable square **LW** widget while an eligible text field and keyboard are active. It hides when the keyboard closes or you leave the field; hardware keyboard sessions use the live editor instead. Tap it for a scrolling correction sheet with marked text and expandable correction cards. An open sheet retains its editor while you review corrections, including temporary IME changes. A sideloaded app may require **Allow restricted settings** in its app-info menu first. Android's accessibility overlay does not require a separate “Display over other apps” grant. Your current keyboard can remain selected.

External text receives red underlines only when its host app exposes exact character rectangles through Android accessibility. Otherwise the correction sheet still shows underlined words in a marked preview; its ranges are clickable to select the corresponding card. The assistant never guesses external font/layout coordinates or rewrites a field simply to inject visual spans.

For the optional keyboard, choose **Enable keyboard**, enable **Local Writer keyboard** in system settings, then **Choose keyboard**. Password fields get typing keys without grammar/rewrite controls. Fields lacking reliable text offsets decline correction commits; share text to the editor instead.

## Verify

```sh
python3 tools/test.py
python3 tools/verify.py dist/local-writer-arm64.apk
```

Host tests exercise the actual ONNX engine, contraction edits, whitespace preservation, long documents, cancellation, UTF-16 offsets, invalid edits and caret mapping. APK checks cover signatures (during build), permissions, service declarations, bundled model hashes, native libraries and 16 KiB alignment. Physical-device results are recorded in [validation](docs/VALIDATION.md). No emulator is used.

Our source is MIT licensed. Bundled dependencies and model weights retain their own licenses, including the grammar model's noncommercial restriction; see [third-party notices](THIRD_PARTY.md).
