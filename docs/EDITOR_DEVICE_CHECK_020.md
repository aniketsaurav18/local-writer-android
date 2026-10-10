# Editor checks on the connected phone — 0.2.0

Performed on 2026-10-11 using ADB on the owner's connected OnePlus CPH2707, after the owner explicitly requested wrong-sentence examples in the already-open Editor. This authorization superseded the earlier request to leave runtime testing to the owner, for this bounded editor check. No emulator or app settings changes were used.

| Input | Observed result |
| --- | --- |
| She go to school every day. | One Agreement correction, go → goes. Accept changed the draft; Undo restored the original and its finding. |
| This sentense has a speling mistake. | Two Spelling findings and both words underlined. The first card proposed sentense → sentence. |
| She don't like apples. They was happy yesterday. | Two actionable corrections. Bulk acceptance produced She doesn't like apples. They were happy yesterday. An additional nonactionable warning remained. |
| I have went to school. | One Grammar correction, went → gone. |
| I look forward to meet you. | One Suggested correction, meet → meeting, demonstrating the contextual model path. |
| He bought a umbrella. | One actionable correction, a → an. |
| She goes to school every day. | Zero corrections and no advice after accepting the initial example. |
| The weather is lovely today. | Zero actionable corrections, but a false nonactionable warning and red underline on weather. |

The last warning says “You may be missing a preposition here.” It is the pinned Harper MissingPreposition heuristic: its adjective/noun POS pattern can mistake temporal modifiers such as today/yesterday for missing-preposition constructions. This is an observed false positive, not a proposed corrected sentence. The warning has Dismiss but no Accept action, and does not inflate the actionable count. Dismiss removed the warning and its markup; rechecking unchanged text kept it dismissed. The default treatment of uncertain warnings still needs refinement; no phrase-specific exception was added during this check.

The editor's red underlines, issue counts and old/new card text were observed. Accept, compatible bulk acceptance, Undo, card expansion and warning Dismiss were exercised as described. These examples are not a held-out accuracy evaluation or latency benchmark; ADB hierarchy capture adds delay. External-app floating overlays, rewriting/tone, optional keyboard, personal dictionary and all text-field types were not exercised in this run.

Raw screenshots and hierarchy captures are retained locally in ignored build/evidence/editor-020/. The original demonstration draft, She go to school every day., is restored and the keyboard is hidden. No text was shared or sent to another app.
