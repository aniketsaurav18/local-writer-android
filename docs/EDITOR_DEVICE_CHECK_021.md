# Editor fixes and device checks — 0.2.1

Checked on 2026-10-11 on the connected physical OnePlus CPH2707 (`3C161300WXZ00000`) using ADB. Package `dev.localwriter`, version name **0.2.1**, version code **9**. The owner requested continuation of the supplied 0.2.0 findings. All input was synthetic and confined to Local Writer's Editor. Android permissions, accessibility settings and keyboard selection were unchanged. No emulator was used.

## Changes

- Neural word-order diffs now join a word's removal and insertion into one correction, including the intervening text. The same edit conversion serves T5 and the optional legacy tagger. Independent corrections remain separate.
- Context previews display the text produced by that card's correction, with the replacement highlighted. Expanded cards separately show an explicit original → replacement comparison. Accessibility descriptions distinguish suggested text from the change.
- When Harper's missing-space heuristic and spelling checker compete for the same word, the spelling correction takes precedence. A contextual result can promote an existing basic candidate when the engines agree. Alternatives remain available; ambiguous corrections remain excluded from bulk acceptance.
- Punctuation insertions receive the Punctuation title.

## Observed results

| Input / action | Result |
| --- | --- |
| `Can you tell me where is the nearest bus stop?` | One word-order card previewed `Can you tell me where the nearest bus stop is?`. Its individual Accept produced that complete sentence and zero findings. No standalone verb-deletion card remained. |
| `Hey why you are ignoring my messages?` | Two findings: punctuation and a single `you are → are you` correction. Individually accepting word order produced `Hey why are you ignoring my messages?`, with exactly one `you`; only the comma finding remained. |
| `She enjoys to dance.` | Preview showed `She enjoys dancing.` rather than juxtaposed original and replacement text. Acceptance produced that sentence and zero findings. |
| Lowercase `i` in the mixed example | The preview showed `Hey I recieved…`. Bulk acceptance capitalized both pronouns without adding another pronoun. |
| Manual repair of `I recieved the address.` | Moved to the known end of the field, stepped back across ` the address.`, deleted the misspelled word and typed `received` character by character. The actual draft was verified as `I received the address.`. Its prior finding cleared; both the capture before hiding the keyboard and the settled capture showed zero suggestions. |
| `Please send me a mesage.` | The interim build reproduced the bad `me sage` default and exposed `message` as an alternative. After the ranking fix, the final build defaulted to `message`, retained other choices, and individual Accept produced `Please send me a message.` with zero findings. |
| Paced typing: `Hey i recieved your mesage but i didnt understood what you means.` | Seven initial findings covered both capitals, `recieved`, `mesage`, `didnt`, `understood` and `means`. Bulk acceptance repaired both capitals and `understood → understand`; the next pass exposed the comma after `Hey`. Reviewing and accepting the remaining cards produced `Hey, I received your message but I didn't understand what you mean.`. |
| Contextual follow-up on that grammatical mixed sentence | T5 additionally proposed `mean → meant`. Accepting this optional tense choice produced `Hey, I received your message but I didn't understand what you meant.` and zero findings. This extra change is not evidence of another grammatical error. |

Paced cases used individual character input, spaces and short pauses. The draft was read back before assessing each case, preventing ADB setup errors from being attributed to the checker. The automation identifies the draft by its `Start writing here…` hint so it cannot confuse the separate rewrite-instruction field with the draft.

## Checks and evidence

`python3 tools/test_correction_diff.py` passed focused JVM regression checks for independent word-order acceptance, source offsets, Unicode, spelling candidate arbitration, preservation of alternatives and stale-finding removal. These checks do not execute or benchmark the neural models.

The release build completed successfully, including APK signature verification, offline manifest/package checks, bundled-model hashes, required native libraries and ZIP alignment. The final APK was installed using `adb install --no-incremental -r`; ADB reported **Success**.

Final artifact: `dist/local-writer-arm64.apk`, SHA-256 `f9e364b038f366db4145696a2837f3ba67971d2dc817dc4949a9f2ab111ae270`.

Raw XML and screenshots are retained under ignored `build/evidence/editor-021/`. Key captures: `embedded-expanded`, `embedded-individual-accepted`, `direct-question-expanded`, `direct-question-individual-accepted`, `typo-manually-repaired-typed`, `typo-manually-repaired`, `final-message-expanded`, `final-message-accepted`, `mixed-all-cards-*`, `mixed-bulk-accepted`, `mixed-mean-accepted`, `mixed-optional-tense-accepted`, `enjoys-preview`, `enjoys-accepted` and `final-restored`. Word-order and manual-repair checks used the interim 0.2.1 build; their code paths were unchanged in the final build. Final-message, mixed and enjoys checks used the final installed APK.

The original demonstration draft, `She go to school every day.`, was restored and the keyboard hidden.

## Remaining limits

The contextual model still counts a valid optional tense change as a correction. These examples do not establish calibrated precision, detection accuracy, latency or memory use. The previous Harper false warning on `The weather is lovely today.` was not changed or retested in this continuation. External-app overlays, rewriting/tone, the optional keyboard, dictionary settings and other text-field types were not exercised here.
