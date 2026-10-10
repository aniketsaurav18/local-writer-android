# Local Writer

An independently implemented Kotlin Android writing assistant with bundled offline grammar and rewrite models. No account, telemetry SDK, runtime downloads, or internet permission. Package: `dev.localwriter`. Android 13+ on ARM64.

The app provides an editor with correction underlines and suggestion cards, a draggable assistant over other apps, an optional writing keyboard, accept/dismiss/accept-all, Undo, personal dictionary, app exclusions, pause/resume, writing goals, five English dialects and optional style advice, selected-text rewrites, tone analysis, share-in and Android's selected-text processing action. Rewrites include concise, professional, friendly, expand, improve and translation into English. Native Harper provides the continuous spelling and grammar pass; a separately scheduled Unbabel T5-small model provides contextual corrections after a pause. Both are bundled.

This is original source code. The supplied APK was used to understand the Android accessibility approach; its proprietary classes and assets are not included. Cloud-only quality and behavior cannot be reproduced one-to-one by these smaller local models. Custom editors that omit usable Android accessibility and input-connection interfaces cannot be edited reliably. Passwords are deliberately excluded. See [accessibility implementation and limits](docs/ACCESSIBILITY.md).

Basic checking uses the maintained Harper core through Rust/JNI. Contextual checking uses sentence-scoped Unbabel T5-small, converted to Q8_0 GGUF and run through llama.cpp. See [Harper integration](docs/HARPER_INTEGRATION.md) and [contextual model](docs/CONTEXT_MODEL.md). There are no app-specific phrase exceptions. Neural suggestions require your review; they have no calibrated confidence or invented explanation. Version 0.2.2 removes the retired ONNX tagger, old spelling engine and their dependencies; saved Legacy selections now use T5. Obsolete private model copies are removed when the app next prepares a model.

The [offline engine research and proposed architecture](docs/OFFLINE_ENGINE_RESEARCH.md) compares Harper, LanguageTool, all requested neural checkpoints, Android runtimes, and the current UI/data flow. Version 0.2.0 introduced native Harper checking, separately scheduled contextual correction, a shared issue store for the external badge and panels, and bounded heavy-model residency. Version 0.2.1 adds grouped word-order corrections, accurate correction previews and improved spelling candidate selection, and is installed on the connected phone. [Editor checks and remaining limits](docs/EDITOR_DEVICE_CHECK_021.md) record observed behavior; broader accuracy and performance remain to be evaluated.

## Build

Use Python 3.12+, JDK 17+, CMake, Rust 1.93 with the `aarch64-linux-android` target, Android SDK platform/build-tools 36.0.0 and NDK 27.1.12297006. `ANDROID_SDK_ROOT` defaults to `~/Android/Sdk`. The SDK command-line build avoids Gradle daemons and uses two native compilation threads and a 640 MiB Kotlin compiler heap. It never starts an emulator.

Inputs are already prepared in this workspace. For a fresh checkout:

```sh
python3 tools/bootstrap.py
# Prepare the context model as described in docs/CONTEXT_MODEL.md:
build/context-venv/bin/python tools/prepare_context_model.py
python3 tools/build.py
```

`bootstrap.py` downloads the hash-checked rewrite model and pinned llama.cpp source. To reuse the supplied files instead of downloading them, add `--inputs /path/to/oss-grammerly`. The context preparation step requires the pinned Python conversion dependencies and PyTorch described in the model document. The build downloads the standalone Kotlin 2.2.20 compiler on its first run and obtains pinned Harper source/Cargo dependencies when needed, compiling Rust with one job. These are development downloads; the installed app performs all inference in-process. Large model/vendor/build files are intentionally excluded from Git; pinned versions and SHA-256 hashes are in `dependencies.lock.json`, `context-model.lock.json`, and `native/harper/Cargo.lock`; the built Harper metadata is under `build/harper/android/build-info.json`. A four-cast upstream compiler compatibility patch is recorded under `native/harper/patches`.

Output: `dist/local-writer-arm64.apk` and `dist/SHA256SUMS`. Preserve the private development signing key in `build/signing/local-writer.p12` for compatible updates. It is excluded from Git. The APK includes the contextual grammar model and rewrite/tone model; unpacked native libraries and the first-use private model copies need additional device storage.

## Editing in Zed

Open this repository root, with Zed's Kotlin extension installed. Because this project uses a Python build instead of Gradle, the executable `kls-classpath` supplies the language server with the Android SDK, Kotlin standard library and generated Android resources. After the initial build prepares dependencies and `R.java`, it creates a small resource-only JAR under `build/editor`; application code is resolved from live source files.

The project-local `.zed/settings.json` selects `kotlin-language-server`, matches the build's JVM target of 11 and limits the server's Java heap to 512 MiB. Background symbol indexing is disabled to reduce memory use; error diagnostics remain enabled. If an already-open window keeps old missing-dependency errors, run **editor: restart language server** from Zed's command palette. `./kls-classpath` prints the resolved dependency paths and reports any missing build inputs.

## Install and enable

```sh
adb install --no-incremental -r dist/local-writer-arm64.apk
adb shell am start -n dev.localwriter/.MainActivity
```

Open **Local Writer → Set up writing assistant**, turn on **Use Local Writer assistant** in Android accessibility settings, and allow access. The app does not request Android's accessibility button or handle shortcut clicks. Android may still offer its generic shortcut setting; an existing shortcut from earlier builds may need to be disabled once in system settings. The app shows one draggable **30dp circular** widget while an eligible text field and keyboard are active. It hides when the keyboard closes or you leave the field; hardware keyboard sessions use the live editor instead. The badge shows a live suggestion count (up to **99+**), a logo when clear, and a spinner while checking, retaining available correction counts during contextual work. A small amber indicator denotes a failed check while available suggestions remain usable.

Tap the badge to open a compact inline correction. Tap its short edit to apply it, or the trash icon to dismiss it. Tap the counter to expand the full-width suggestion list above the keyboard; long edits also offer a **Review suggestions** control. The list uses expandable cards with rule explanations where available, alternatives, spelling dictionary actions, and Accept/Dismiss actions, a fixed header and an icon toolbar for corrections and local rewriting/tone. Collapse returns to the compact card. More options include marked text, acceptance of compatible corrections with a single replacement, Undo, pause and app exclusions. Nonactionable warnings and optional style advice appear separately from the badge count. An open panel retains its editor while you review corrections, including temporary IME changes. A sideloaded app may require **Allow restricted settings** in its app-info menu first. Android's accessibility overlay does not require a separate “Display over other apps” grant. Your current keyboard can remain selected.

External text receives red underlines only when its host app exposes exact character rectangles through Android accessibility. Otherwise the correction sheet still shows underlined words in a marked preview; its ranges are clickable to select the corresponding card. The assistant never guesses external font/layout coordinates or rewrites a field simply to inject visual spans.

For the optional keyboard, choose **Enable keyboard**, enable **Local Writer keyboard** in system settings, then **Choose keyboard**. Password fields get typing keys without grammar/rewrite controls. Fields lacking reliable text offsets decline correction commits; share text to the editor instead.

## Verify

```sh
python3 tools/verify.py dist/local-writer-arm64.apk
python3 tools/test_correction_diff.py
```

The focused 0.2.1 JVM checks cover edit grouping, spelling candidate selection, Unicode/source offsets and stale findings. Owner-authorized ADB Editor checks are recorded for [0.2.0](docs/EDITOR_DEVICE_CHECK_020.md) and [0.2.1](docs/EDITOR_DEVICE_CHECK_021.md), including remaining model limitations. These are not a model-quality benchmark. The retired ONNX test harness and its desktop dependencies have been removed; current correction regression tests are retained. APK checks cover signatures (during build), permissions, service declarations, bundled model hashes, native libraries and 16 KiB alignment. Physical-device results are recorded in [validation](docs/VALIDATION.md). No emulator is used.

Our source is MIT licensed. Bundled dependencies and model weights retain their own licenses; see [third-party notices](THIRD_PARTY.md).
