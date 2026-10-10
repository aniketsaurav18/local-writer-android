#!/usr/bin/env python3
"""Download a pinned T5 checkpoint and convert weights/tokenizer, never run inference."""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
MODEL = "Unbabel/gec-t5_small"
REVISION = "c958d53bfbce19c87342b69fc6bcaba7303d076f"
LLAMA_REVISION = "aa94f2086147cd4bfbcff818ce998dffce5fcb39"
SOURCE_FILES = (
    "config.json", "pytorch_model.bin", "spiece.model", "tokenizer.json",
    "tokenizer_config.json", "special_tokens_map.json", "README.md",
)
ASSET = "app/src/main/assets/local-model/context-t5-small-q8.gguf"


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def blob_digest(path: Path) -> str:
    value = hashlib.sha1(f"blob {path.stat().st_size}\0".encode())
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def download(path: Path, url: str, info: dict) -> dict:
    lfs_hash = info.get("lfs", {}).get("sha256")
    expected_size = info["size"]
    def verified() -> bool:
        if not path.is_file() or path.stat().st_size != expected_size:
            return False
        return digest(path) == lfs_hash if lfs_hash else blob_digest(path) == info["blobId"]
    if not verified():
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(path.suffix + ".part")
        print(f"Downloading {path.name} ({expected_size:,} bytes)", flush=True)
        with urllib.request.urlopen(url, timeout=120) as source, temporary.open("wb") as target:
            while block := source.read(1024 * 1024):
                target.write(block)
        temporary.replace(path)
        if not verified():
            path.unlink()
            raise RuntimeError(f"Pinned source verification failed: {path.name}")
    return {"file": path.name, "url": url, "bytes": expected_size, "sha256": digest(path)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=ROOT / "build/context-source")
    parser.add_argument("--output", type=Path, default=ROOT / ASSET)
    args = parser.parse_args()
    source = args.source.resolve()
    output = args.output.resolve()
    llama = ROOT / "vendor/llama.cpp"
    # bootstrap.py vendors a pinned source archive without a .git directory.
    actual = json.loads((ROOT / "vendor/llama-revision.json").read_text())["sha"]
    if actual != LLAMA_REVISION:
        raise RuntimeError("Context conversion requires the app's pinned llama.cpp revision")
    with urllib.request.urlopen(f"https://huggingface.co/api/models/{MODEL}/revision/{REVISION}?blobs=true", timeout=60) as stream:
        metadata = json.load(stream)
    if metadata["sha"] != REVISION:
        raise RuntimeError("Unexpected source checkpoint revision")
    files = {entry["rfilename"]: entry for entry in metadata["siblings"]}
    sources = [download(source / name, f"https://huggingface.co/{MODEL}/resolve/{REVISION}/{name}", files[name])
               for name in SOURCE_FILES if name in files]
    required = {"config.json", "pytorch_model.bin", "spiece.model", "tokenizer_config.json"}
    if not required.issubset({entry["file"] for entry in sources}):
        raise RuntimeError("Pinned source lacks required weights/tokenizer")
    config = json.loads((source / "config.json").read_text())
    if config["architectures"] != ["T5ForConditionalGeneration"] or config["decoder_start_token_id"] != 0 or config["eos_token_id"] != 1:
        raise RuntimeError("Unexpected T5 architecture or special token contract")
    # This checkpoint stores T5 extra IDs in tokenizer.json, but the pinned
    # converter's T5 branch reads extras only from added_tokens.json. Derive that
    # auxiliary file losslessly, without changing the source tokenizer files.
    from sentencepiece import sentencepiece_model_pb2
    tokenizer_model = sentencepiece_model_pb2.ModelProto()
    tokenizer_model.ParseFromString((source / "spiece.model").read_bytes())
    tokenizer = json.loads((source / "tokenizer.json").read_text())
    added = {entry["content"]: entry["id"] for entry in tokenizer.get("added_tokens", [])
             if entry["id"] >= len(tokenizer_model.pieces)}
    if any(token_id >= config["vocab_size"] for token_id in added.values()):
        raise RuntimeError("Additional tokenizer IDs exceed the model vocabulary")
    derived = source / "added_tokens.json"
    derived.write_text(json.dumps(added, ensure_ascii=False, sort_keys=True) + "\n")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(".part.gguf")
    command = [sys.executable, str(llama / "convert_hf_to_gguf.py"), str(source),
               "--outfile", str(temporary), "--outtype", "q8_0", "--use-temp-file",
               "--model-name", "Unbabel GEC T5 Small"]
    environment = {**os.environ, "OMP_NUM_THREADS": "2", "MKL_NUM_THREADS": "2",
                   "HF_HUB_OFFLINE": "1", "TRANSFORMERS_OFFLINE": "1", "TOKENIZERS_PARALLELISM": "false"}
    print("Converting pinned T5 weights to Q8_0 GGUF (no inference)", flush=True)
    subprocess.run(command, check=True, env=environment)
    with temporary.open("rb") as stream:
        if stream.read(4) != b"GGUF":
            raise RuntimeError("Converter did not produce a GGUF")
    temporary.replace(output)
    versions = {name: importlib.metadata.version(name) for name in
                ("torch", "numpy", "transformers", "sentencepiece", "protobuf")}
    lock = {
        "model": MODEL, "revision": REVISION, "llama_revision": LLAMA_REVISION,
        "license": "Apache-2.0 (upstream model card)", "quantization": "Q8_0",
        "conversion_packages": versions,
        "conversion_options": ["--outtype", "q8_0", "--use-temp-file", "--model-name", "Unbabel GEC T5 Small"],
        "sources": sources,
        "derived_tokenizer": {"file": "added_tokens.json", "sha256": digest(derived), "tokens": len(added)},
        "asset": {"path": ASSET, "bytes": output.stat().st_size, "sha256": digest(output)},
        "validation": "Source/hash and container checks only; no generation, accuracy or latency test.",
    }
    lock_path = ROOT / "context-model.lock.json"
    lock_path.write_text(json.dumps(lock, indent=2) + "\n")
    print(f"Prepared {output.name}: {output.stat().st_size:,} bytes; lock: {lock_path}", flush=True)


if __name__ == "__main__":
    main()
