# Offline contextual grammar correction

The contextual checker uses [Unbabel/gec-t5_small](https://huggingface.co/Unbabel/gec-t5_small), pinned at `c958d53bfbce19c87342b69fc6bcaba7303d076f`. This is a sentence correction encoder-decoder, not a chat model. The source card declares Apache-2.0. It is converted into `context-t5-small-q8.gguf` with the existing vendored llama.cpp revision `aa94f2086147cd4bfbcff818ce998dffce5fcb39`.

## Input and inference contract

The Kotlin adapter uses an English `BreakIterator` to locate sentence ranges. It trims only boundary whitespace, retains UTF-16 source offsets, and caches up to 64 unchanged sentences in memory. Complete sentences longer than 1,200 UTF-16 code units fail the contextual pass rather than being clipped or silently marked as checked. The native adapter independently enforces a maximum of 512 input tokens, including the `gec: ` prefix and EOS; an over-budget source fails without producing a correction. The fast rule checker can still finish independently. The caches contain no persistent text or logs.

Native inference encodes the complete input batch once, then starts the decoder at the checkpoint's padding token (ID 0), with EOS ID 1. Both encoder and decoder run on CPU with two threads. Greedy generation has a 512-token output bound and must reach EOS. A clipped or empty generation is rejected so it cannot be interpreted as an instruction to delete the source's tail. JNI uses standard UTF-8 byte conversions rather than JNI modified UTF-8, which can corrupt supplementary Unicode characters.

The decoder has its own model state, separate from the optional Qwen rewriting model. Contexts, batches and samplers are freed after each sentence. `cancel()` invalidates active requests, including model loading and CPU graph evaluation; `release()` cancels and frees the retained T5 model under its native mutex. The heavy worker releases the other heavy model before switching to contextual checking, rewriting or tone, and unloads idle weights after 30 seconds. A cancellation epoch captured before JNI entry prevents an obsolete request from starting after cancellation. Call `check()` and `close()` on the checking worker; the sentence cache is confined to that worker.

`TextLogic.diff` converts the corrected sentence into suggestions addressing the original text. The adapter returns edits; it never commits a full rewrite or modifies a field. These suggestions do not contain a model-generated linguistic explanation or calibrated confidence. The app's suggestion merger must reject overlaps and stale field revisions before showing or accepting them.

## Tokenizer source inspection

The source is T5's SentencePiece unigram model. The pinned converter copies its vocabulary scores/types, normalizer's precompiled character map, dummy-prefix flag and whitespace-removal flag into GGUF. llama.cpp's `t5` tokenizer selects the unigram implementation with pad 0, EOS 1 and unknown 2. The adapter adds special framing and explicitly checks for the terminal EOS. Input is treated as text, with special-token parsing disabled.

The checkpoint's extra IDs are in `tokenizer.json`; it has no upstream `added_tokens.json`. The pinned converter's T5 path only reads additional vocabulary from the latter. `prepare_context_model.py` therefore derives that auxiliary file from the source tokenizer's IDs beyond the SentencePiece piece count, retaining the exact IDs and recording its digest in the context lock. The source files are not altered. This preserves the complete T5 vocabulary instead of leaving extra IDs as unused padding entries.

This is source-level contract alignment. Tokenizer parity, output quality and Unicode behavior have not been checked by running the model.

## Reproducible preparation

The script fetches only the pinned checkpoint files, verifies Hugging Face's LFS SHA-256 or Git blob digest and byte size, and records every source SHA-256. It verifies the vendored source revision, architecture and special-token IDs. Conversion uses lazy tensor handling and a temporary tensor file; no model generation or benchmark runs during preparation.

The preparation performed here uses a small development virtualenv sharing the existing PyTorch installation:

```sh
python3 -m venv --system-site-packages build/context-venv
build/context-venv/bin/pip install numpy==2.2.6 transformers==4.57.6 sentencepiece==0.2.1 protobuf==4.25.8
build/context-venv/bin/python tools/prepare_context_model.py
```

PyTorch 2.6.0 was already available. All actually used package versions are recorded in `context-model.lock.json`; this also records source revisions, conversion options, the derived tokenizer file and output byte size/SHA-256. The Android app performs no downloads; this is a developer preparation step. Large source weights, virtualenv and bundled model remain outside Git.

Q8_0 is an eight-bit block weight format, not an ONNX INT8 graph. Its actual output size comes from the lock file, not a parameter-count estimate. Tensor sharing and float normalization tensors affect the final size. Its effects on quality and speed have not been measured.

## Quality limits

The upstream model card's example uses five-beam decoding. The mobile adapter uses greedy generation for bounded resource use; it does not reproduce that example's generation settings. The card references an F0.5 score of 60.70, while the current [referenced paper's Table 4](https://arxiv.org/html/2106.03830) reports T5-small 60.54 on CoNLL-2014 and 65.01 on BEA. None of those establish the quantized Android checker's performance. No accuracy, inference, emulator or phone tests were run for this integration. It may miss mistakes, propose unwanted changes or fail a long sentence; those cases require user review.
