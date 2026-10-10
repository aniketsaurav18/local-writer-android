# Offline Harper integration

Local Writer embeds `harper-core` **2.12.0**, pinned to
`b0ab21f5500da525c76553c56ca1b6b7669c6a78` from
[Automattic/harper](https://github.com/Automattic/harper/tree/b0ab21f5500da525c76553c56ca1b6b7669c6a78).
The upstream repository and core package declare Apache-2.0. The extracted
upstream license is at `build/harper/upstream/LICENSE`; retain the applicable
upstream and dependency notices in distributions.

`native/harper/generate_notices.py` selects the active locked Android normal and
build dependency graph, preserves its license/copyright/notice texts, and writes
`native/harper/THIRD_PARTY_NOTICES.txt` plus the identical packaged asset
`app/src/main/assets/notices/HARPER-DEPENDENCIES.txt`. The build invokes this
generator after native compilation. Optional unused Cargo packages are excluded.
Workspace packages missing license texts inherit them from precisely the same
source commit; pinned supplemental licenses for Embassy and JNI macros live in
`native/harper/licenses/`. Bundled Brill/Burn model resources are also attributed.

## Build

Run `python3 tools/build_harper.py` from this repository. It uses the existing
verified sibling `../harper` checkout, or clones release `v2.12.0` into
`build/harper/source` when that checkout is absent. `--source PATH` selects a
different checkout, whose HEAD must match the exact pin. Upstream worktree
edits are not imported: the build materializes committed source with
`git archive`. Only the core, Brill, and POS utility crates are imported. Cargo
also needs the optional thesaurus package manifest and source for resolution;
the script downloads those at the same pin, excluding its unused 24 MB data.
The thesaurus feature is disabled.

The extracted build copy receives one explicitly recorded compatibility patch:
`native/harper/patches/rust193-predicate-coercion.json`. Rust 1.93 rejected four
heterogeneous predicate function arrays in three pinned upstream rules. The
patch adds explicit function-pointer casts to the first element of each array,
without changing predicates, matching, or suggestions. Each changed file
carries a modification notice. The original upstream checkout is untouched;
the patch hash is included in `build-info.json`.

Requirements: Rust with edition 2024 support, rustup, Python 3.12+, git, and
Android NDK 27.1.12297006. `ANDROID_SDK_ROOT`, `ANDROID_NDK_HOME`, and `--ndk`
can select the installed SDK/NDK. The build installs the Rust Android ARM64
standard library if missing. Cargo is limited to **one job**, release
optimization level 2, eight codegen units, and no LTO. The committed adapter
`Cargo.lock` fixes the resolved Rust dependency versions; subsequent builds use
`--locked`.

Output: `build/harper/android/liblocalharper.so`, for `arm64-v8a` and Android
API 33. Package it as `lib/arm64-v8a/liblocalharper.so`. A companion
`build-info.json` records the source revision, target, NDK, size, and SHA-256.
The linker uses 16 KiB maximum/common page-size flags and the script checks
ELF LOAD alignment statically. The APK build still must apply 16 KiB ZIP
alignment. Compilation and ELF inspection do not establish runtime behavior
or compatibility on a 16 KiB device.

## Boundary contract

`HarperEngine.check(text, dialect, dictionary, style, active)` returns the app's
`Edit` records. The private instance JNI method is:

```kotlin
external fun lint(
    text: String,
    dialect: String,
    personalDictionary: Array<String>,
    style: Boolean
): String
```

The export is `Java_dev_localwriter_HarperEngine_lint`. It returns a JSON array
with `begin`, `end`, `replacement`, `alternatives`, `category`, `explanation`,
`ruleId`, `actionable`, `priority`, and `style`. It throws an
`IllegalStateException` on native failures rather than reporting an empty
result as if checking succeeded. JNI pending exceptions are preserved, and
Rust unwinding is caught before crossing the JNI boundary.

Native Harper spans index Unicode scalar values and are end-exclusive. The
adapter converts both boundaries to Android/Kotlin UTF-16 code-unit offsets
with a single cumulative map. These are direct spans, not inferred word diffs.
`ReplaceWith` replaces the marked range; `Remove` supplies an empty replacement;
`InsertAfter` becomes an insertion with `begin == end == lint.end`. Alternatives
are deduplicated strings with the same edit operation/range as the primary
suggestion. No-op suggestions are removed. Findings without an applicable fix
keep the original visible range/text and have `actionable = false`, so callers
must not offer Accept or include them in Apply All.

`LintGroup.organized_lints` supplies actual upstream public rule IDs, including
the public group identifier for grouped rules. Lower numerical `priority`
means more important. Multiple upstream findings may overlap; the app's issue
store owns display/acceptance arbitration. The native adapter does not drop
overlapping warnings or competing suggestions.

## State and settings

The process retains one curated checker protected by a mutex. It is rebuilt
only when the dialect or sorted unique personal dictionary changes. Custom
words are added using `MutableDictionary`, merged with the embedded curated
FST dictionary, and used for both document parsing and spelling suggestions.
Dialect names and BCP-47 values support American (`en-US`), British (`en-GB`),
Canadian (`en-CA`), Australian (`en-AU`), and Indian (`en-IN`) English.
Unknown dialect names produce an explicit error.

The curated upstream rule configuration remains in effect. With `style = false`,
the adapter excludes findings in `Style`, `Enhancement`, `Readability`, and
`Redundancy`; enabling style includes those curated findings. It does not
enable every subjective upstream rule. All other categories remain available.

The caller runs checking on a background worker. `active` is checked before
native work, after its completion, and during result decoding. A superseded
native call finishes rather than being forcibly interrupted; stale results
are discarded with `CancellationException`. Apply suggestions only to the
same validated field/text snapshot that produced them.

## Runtime scope

This embeds the Rust core directly, without harper.js, WASM, WebView, the
language server, remote services, telemetry, or local-statistics wrappers.
The core's dictionaries, configuration, rules, POS tagger, and chunker data
are embedded at compilation. No runtime download or login is needed.

Current Harper is not exclusively a dictionary/regex engine:
`Document::parse` performs Brill POS tagging and CPU neural noun-phrase
chunking. The embedded chunker vocabulary is 627,993 bytes and the model is
806,312 bytes. Burn uses its ndarray CPU backend without GPU or training
features. These static resource sizes do not measure APK size, runtime RAM,
latency, or grammar accuracy. No app, device, emulator, or inference testing
is part of this integration build.
