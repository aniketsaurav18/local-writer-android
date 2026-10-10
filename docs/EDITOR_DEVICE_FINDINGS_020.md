# Local Writer 0.2.0 — continuing device findings

Device: OnePlus CPH2707 (`3C161300WXZ00000`), physical device via ADB. Testing remains in the Local Writer Editor. No Android permissions/settings changed. Evidence: `build/evidence/editor-020/`.

## Running observations

| Case / action | Observed result | Assessment |
| --- | --- | --- |
| Starting draft: `She enjoys to dance.` | Initial Editor hierarchy showed `1 suggestion` / `1 correction`. The correction preview displayed `She enjoys to dance dancing.` | **Anomaly:** preview duplicates “dancing” rather than presenting the replacement sentence recorded in the earlier report (`She enjoys dancing.`). Need inspect alternatives and acceptance behavior. |
| Enjoys-to-dance acceptance | Tapped `Accept 1 compatible corrections`; draft became `She enjoys dancing.` with `3 words · 0 suggestions`, and the correction cards cleared. | Accepted result is grammatical and complete. The initial preview (`...dance dancing.`) was misleading relative to the accepted result. |
| Paced typing, first burst `hey i recieved ` | Saved hierarchy showed `Hey i recieved` and `3 words · 2 suggestions`; correction preview read `Hey i I recieved` under “Capitalization”. A second “Spelling” card was below the fold. | **UI anomaly:** capitalization replacement appears to duplicate the pronoun (`i I`) rather than simply capitalize it. Keyboard autocapitalized initial `hey` to `Hey`. Partial evidence only. |
| Paced session continuation | A later hierarchy capture showed Android Launcher instead of Editor after subsequent input; the rest of this draft could not be captured. | Session interrupted; no conclusion about later checks. Relaunched app. |
| `Hey why you are ignoring my messages?` | Three suggestions appeared: add comma; remove the extra “you”; and another “Add missing text” proposal inserting a second “you” before “ignoring”. `Accept 3 compatible corrections` produced `Hey, why are you ignoring my messages?` and cleared all findings. | Bulk outcome is grammatical and complete. One individual proposal is clearly wrong (duplicate “you”). |
| `Can you tell me where is the nearest bus stop?` | Two suggestions appeared. One card showed `is` struck out, which alone would leave “where the nearest bus stop” without a verb. Bulk acceptance produced `Can you tell me where the nearest bus stop is?` and zero findings. | Combined outcome is a complete grammatical embedded question; the individual deletion preview is incomplete if accepted alone. |
| Test input setup error | Two draft attempts were malformed by ADB `%s` space encoding and yielded `...wheresis...`, then `bus top`; both were overwritten before evaluation. | Harness/input issue only; excluded from app-quality results. Raw XML/screenshots retained. |
| Typo edit experiment | `I recieved the address.` showed the expected spelling suggestion. Cursor movement via ADB keycodes was inaccurate twice and yielded `Ireceivedd…`, then `receiveded…`; the spelling finding remained, so this did **not** verify stale-suggestion clearing. | Inconclusive; automation cursor placement caused malformed edits. Original demonstration draft restored at end. |

## Cases to run

- [x] Accept and inspect the current “enjoys to dance” correction; accepted draft was grammatical.
- [ ] Complete mixed conversational sentence with spelling and grammar mistakes; partial paced typing was captured.
- [x] Word-order examples; compatible bulk acceptance was complete and grammatical in two cases.
- [ ] Repair an existing typo via cursor/backspace and confirm stale findings clear; inconclusive due ADB cursor placement errors.
- [x] Restore `build/evidence/editor-020/batch2-original-draft.txt` and capture final state.

## Status and limits

Successful observations: spelling preview for `recieved`; real-time analysis during burst typing; the `Hey why you are ignoring…` word-order repair when accepting compatible proposals together; and embedded-question word order repair when accepting compatible proposals together. The embedded-question bulk result was grammatical and complete. Both cases also exposed at least one misleading individual proposal.

Still unverified in this continuation: correcting a typo in place and confirming its prior suggestion disappears; the broader mixed conversational sentence with spelling and grammar errors; and reviewing all alternatives for the earlier `mesage` → `me sage` anomaly. Typing/hierarchy interaction on this phone was unstable at times, and ADB `%s` escaping/cursor keycodes caused setup errors; those are excluded from app-quality claims.

The saved original draft `She go to school every day.` was restored. Final capture: `build/evidence/editor-020/final-restored.xml` and `.png`. Keyboard hidden. No permissions/settings changed; no rebuild or rule changes made.
