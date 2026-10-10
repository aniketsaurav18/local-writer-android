#!/usr/bin/env python3
"""Build the pinned offline Rust engine for Android ARM64 with one Cargo job.

Does not run inference, tests, an emulator, or the Android application.
"""
from pathlib import Path
import argparse
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
REVISION = "b0ab21f5500da525c76553c56ca1b6b7669c6a78"
REPOSITORY = "https://github.com/Automattic/harper.git"
TARGET = "aarch64-linux-android"
NDK_VERSION = "27.1.12297006"
CRATES = ["harper-core", "harper-brill", "harper-pos-utils"]


def run(*args, **kwargs):
    print("Running:", " ".join(map(str, args)), flush=True)
    return subprocess.run(list(map(str, args)), check=True, **kwargs)


def revision(source):
    return subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=ROOT.parent / "harper", help="Read-only pinned Harper checkout; otherwise clone into build/harper/source")
    parser.add_argument("--ndk", type=Path)
    args = parser.parse_args()
    build = ROOT / "build/harper"
    build.mkdir(parents=True, exist_ok=True)
    source = args.source.resolve()
    if not (source / ".git").exists():
        source = build / "source"
        if not (source / ".git").exists():
            run("git", "clone", "--depth", "1", "--filter=blob:none", "--sparse", "--branch", "v2.12.0", REPOSITORY, source)
            run("git", "-C", source, "sparse-checkout", "set", *CRATES)
    if revision(source) != REVISION:
        raise SystemExit(f"Harper checkout must be pinned to {REVISION}: {source}")
    # git archive excludes uncommitted upstream edits, and imports only the
    # core dependency crates rather than the language server or web packages.
    upstream = build / "upstream"
    stamp = upstream / ".revision"
    if not stamp.exists() or stamp.read_text().strip() != REVISION:
        print(f"Preparing Harper {REVISION} from {source}", flush=True)
        if upstream.exists():
            shutil.rmtree(upstream)
        upstream.mkdir()
        archive = subprocess.check_output(["git", "-C", str(source), "archive", REVISION, *CRATES, "LICENSE"])
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            tar.extractall(upstream, filter="data")
        # Cargo resolves optional path manifests even though thesaurus is off.
        # Keep its pinned source package without fetching its unused 24 MB
        # thesaurus data (particularly expensive in a sparse partial clone).
        for relative in ["Cargo.toml", "build.rs", "src/lib.rs", "src/thesaurus.rs", "word-freq.txt"]:
            path = upstream / "harper-thesaurus" / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            url = f"https://raw.githubusercontent.com/Automattic/harper/{REVISION}/harper-thesaurus/{relative}"
            with urllib.request.urlopen(url, timeout=60) as response:
                path.write_bytes(response.read())
        stamp.write_text(REVISION + "\n")
    compatibility = ROOT / "native/harper/patches/rust193-predicate-coercion.json"
    notice = "// Local Writer change: explicit predicate function-pointer coercion for Rust 1.93.\n"
    for change in json.loads(compatibility.read_text()):
        path = upstream / change["file"]
        content = path.read_text()
        if content.count(change["before"]) == 1:
            content = content.replace(change["before"], change["after"], 1)
            if not content.startswith(notice):
                content = notice + content
            path.write_text(content)
        elif content.count(change["after"]) != 1:
            raise SystemExit(f"Harper compatibility patch does not match pinned source: {path}")
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT", Path.home() / "Android/Sdk"))
    ndk = args.ndk or Path(os.environ.get("ANDROID_NDK_HOME", sdk / "ndk" / NDK_VERSION))
    toolchain = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    linker = toolchain / "aarch64-linux-android33-clang"
    if not linker.exists():
        raise SystemExit(f"NDK 27 Android API 33 linker missing: {linker}")
    run("rustup", "target", "add", TARGET)
    env = os.environ.copy()
    env["CARGO_BUILD_JOBS"] = "1"
    env["CARGO_TARGET_DIR"] = str(build / "target")
    env["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"] = str(linker)
    env["CC_aarch64_linux_android"] = str(linker)
    env["AR_aarch64_linux_android"] = str(toolchain / "llvm-ar")
    env["CARGO_ENCODED_RUSTFLAGS"] = "\x1f".join([
        "-C", "link-arg=-Wl,-z,max-page-size=16384",
        "-C", "link-arg=-Wl,-z,common-page-size=16384",
    ])
    manifest = ROOT / "native/harper/Cargo.toml"
    lock = manifest.parent / "Cargo.lock"
    command = ["cargo", "build", "--release", "--target", TARGET, "--jobs", "1", "--manifest-path", manifest]
    if lock.exists():
        command.append("--locked")
    run(*command, env=env)
    built = build / "target" / TARGET / "release/liblocalharper.so"
    output = build / "android/liblocalharper.so"
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(built, output)
    # This is static ELF inspection, not a runtime test.
    headers = subprocess.check_output([str(toolchain / "llvm-readelf"), "-lW", str(output)], text=True)
    loads = [line.split() for line in headers.splitlines() if line.strip().startswith("LOAD ")]
    if not loads or any(int(parts[-1], 16) < 16384 for parts in loads):
        raise SystemExit("Harper ELF LOAD segment alignment is below 16 KiB")
    metadata = {
        "repository": REPOSITORY, "revision": REVISION, "version": "2.12.0",
        "target": TARGET, "api": 33, "ndk": str(ndk),
        "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
        "bytes": output.stat().st_size, "elfLoadAlignment": 16384,
        "compatibilityPatchSha256": hashlib.sha256(compatibility.read_bytes()).hexdigest(),
    }
    (output.parent / "build-info.json").write_text(json.dumps(metadata, indent=2) + "\n")
    run(sys.executable, ROOT / "native/harper/generate_notices.py")
    print(f"Built {output} ({output.stat().st_size} bytes); ELF LOAD alignment >= 16 KiB", flush=True)


if __name__ == "__main__":
    main()
