# Third-party notices

The root MIT license covers this project's original Kotlin, JNI and build-tool code. It does not relicense dependencies or model weights.

| Component | Source/version | License |
| --- | --- | --- |
| Grammar model, labels and tokenizer | [kotoshu/grammar-en](https://huggingface.co/kotoshu/grammar-en), revision `7a64ebe0e1944f9de3c5060cb6cdbd0543826002` | CC-BY-NC-SA-4.0, as declared by its model card; noncommercial use |
| Rewrite/tone model | [Qwen2.5-0.5B-Instruct-GGUF](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF), revision `9217f5db79a29953eb74d5343926648285ec7e67`, Q4_K_M | Apache-2.0 |
| Verb-form table | [Grammarly GECToR](https://github.com/grammarly/gector), pinned revision in dependencies.lock.json | Apache-2.0 |
| ONNX Runtime Android/Java | [Microsoft ONNX Runtime](https://github.com/microsoft/onnxruntime), 1.23.2 | MIT |
| Native rewrite runtime | [llama.cpp](https://github.com/ggml-org/llama.cpp), `aa94f2086147cd4bfbcff818ce998dffce5fcb39` | MIT; copy in vendor/LLAMA-LICENSE |
| Kotlin runtime/compiler | [JetBrains Kotlin](https://github.com/JetBrains/kotlin), 2.2.20 | Apache-2.0 |
| Host test JSON library | [JSON-java](https://github.com/stleary/JSON-java), 20250517 | Public domain |
| English spelling frequency dictionary | [SymSpell](https://github.com/wolfgarbe/SymSpell), revision `c239062ae02961df18ab7da1671d01b4388204e0` | MIT; attribution bundled in notices/SPELLING-LICENSE.txt |

See upstream notices in the downloaded runtime/compiler/source distributions for transitive components. Grammarly's supplied Android APK remains proprietary and is not a dependency of this app. This project is not affiliated with Grammarly.
