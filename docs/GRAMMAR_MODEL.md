# Retired grammar model input and decoding

Historical implementation record: version 0.2.2 removes this engine, its assets and dependency pins. Current contextual checking uses [Unbabel T5-small](CONTEXT_MODEL.md).

The former bundled artifact was `kotoshu/grammar-en` at revision `7a64ebe0e1944f9de3c5060cb6cdbd0543826002`. Its model, labels and byte-BPE vocabulary were hash-pinned in the earlier `dependencies.lock.json`.

Version 0.1.6 corrects general inference mismatches in the original Kotlin implementation:

- Model tokens are whitespace-separated words, retaining attached punctuation and contractions. `TextLogic.words` still provides character-level edit diffs and highlighting; it no longer supplies model tokens.
- Each model token is byte-BPE encoded without a leading space. BOS/EOS frame the concatenated IDs. Every word's first subword position is recorded explicitly and used for its label.
- Label decoding follows argmax, including KEEP. The prior global 0.7 confidence floor was not supplied with this model. A calibrated per-label floor would require a development set; none is bundled.
- Labels are applied in one pass against original token indexes. Adjacent identical predictions are not suppressed, and replacement payloads do not implicitly consume matching subsequent words. The model's DELETE labels handle deletions. MERGE consumes exactly its two input tokens.
- Four apply-and-retag rounds and cycle detection remain, matching the iteration count documented for the pinned artifact.

No phrase-specific grammar rules or advisory-warning fallback are included. The proposed rule-based update was removed before installation.

## Primary source ledger

The public upstream code was inspected at commit `e16aa00dccb96309ad2be8ec7c04a940faab1ee9`:

- [Byte BPE](https://github.com/kotoshu/kotoshu/blob/e16aa00dccb96309ad2be8ec7c04a940faab1ee9/lib/kotoshu/grammar/byte_bpe.rb): `encode_words` uses bare-word BPE; `alignment` assigns pieces to words.
- [Decoder](https://github.com/kotoshu/kotoshu/blob/e16aa00dccb96309ad2be8ec7c04a940faab1ee9/lib/kotoshu/grammar/gector_decoder.rb): first-subword predictions, default confidence floor 0.0, replacement/append/delete/merge semantics.
- [Training preparation](https://github.com/kotoshu/kotoshu/blob/e16aa00dccb96309ad2be8ec7c04a940faab1ee9/scripts/gector_prep.py): whitespace tokenization and explicit deletion tags for many-to-many replacements and merges.
- [Training](https://github.com/kotoshu/kotoshu/blob/e16aa00dccb96309ad2be8ec7c04a940faab1ee9/scripts/train_gector_modal.py): pretokenized input and word-to-piece labels.
- [Model card](https://huggingface.co/kotoshu/grammar-en/blob/7a64ebe0e1944f9de3c5060cb6cdbd0543826002/README.md): closed-vocabulary tagger, four rounds, conservative recall and learner-text domain.

The Kotlin decoder uses simultaneous original-index application rather than the public Ruby decoder's ascending mutation loop, which can shift the targets of later predictions after insertion/deletion. The model card does not pin its exact training-code commit or all benchmark settings; code inspection therefore does not establish benchmark equivalence or confirm any particular correction. The author reports precision 0.615 and recall 0.280 on CoNLL-2014; those are upstream claims, not measurements of this Android app.

The owner requested no host or runtime tests. Only compilation, packaging and build-time asset/signature checks were performed for this update. The screenshot that motivated this investigation remains private in the ignored build directory.
