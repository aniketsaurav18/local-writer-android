#!/usr/bin/env python3
"""Collect license texts for the active, locked Android Cargo dependency graph."""
from pathlib import Path
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
MANIFEST = HERE / "Cargo.toml"
REVISION = "b0ab21f5500da525c76553c56ca1b6b7669c6a78"
TARGET = "aarch64-linux-android"


def cargo(*args):
    return subprocess.check_output([
        "cargo", *args, "--locked", "--offline", "--manifest-path", str(MANIFEST)
    ], text=True)


def vcs_of(package):
    path = Path(package["manifest_path"]).parent / ".cargo_vcs_info.json"
    return json.loads(path.read_text())["git"]["sha1"] if path.exists() else None


def bundled_licenses(package):
    directory = Path(package["manifest_path"]).parent
    files = [path for path in directory.rglob("*") if path.is_file()
             and any(path.name.upper().startswith(prefix) for prefix in
                     ("LICENSE", "LICENCE", "COPYING", "NOTICE", "AUTHORS", "COPYRIGHT"))
             and "tests" not in path.relative_to(directory).parts
             and ".github" not in path.relative_to(directory).parts]
    if package.get("license_file"):
        explicit = directory / package["license_file"]
        if explicit.is_file() and explicit not in files:
            files.append(explicit)
    return [(str(path.relative_to(directory)), path) for path in sorted(files)]


def main():
    metadata = json.loads(cargo("metadata", "--format-version", "1", "--filter-platform", TARGET))
    tree = cargo("tree", "--target", TARGET, "--edges", "normal,build", "--prefix", "none", "--format", "{p}")
    active = {match.groups() for line in tree.splitlines()
              if (match := re.match(r"(\S+) v(\S+)", line))}
    packages = [p for p in metadata["packages"] if (p["name"], p["version"]) in active and p["name"] != "localharper"]
    records = []
    for package in sorted(packages, key=lambda p: (p["name"], p["version"])):
        files = bundled_licenses(package)
        vcs = vcs_of(package)
        if not files and package["name"].startswith("harper-"):
            files = [("Upstream Harper LICENSE", ROOT / "build/harper/upstream/LICENSE")]
        if not files and vcs:
            # Some workspace subcrates omit their root license in the crate
            # archive. Reuse bundled notices from precisely the same VCS commit.
            for sibling in metadata["packages"]:
                if vcs_of(sibling) == vcs and sibling.get("license") == package.get("license"):
                    inherited = bundled_licenses(sibling)
                    if inherited:
                        files = [(f"Same-commit workspace license from {sibling['name']}: {label}", path)
                                 for label, path in inherited]
                        break
        if not files and vcs:
            files = [(f"Pinned upstream {path.name}", path) for path in sorted((HERE / "licenses").glob(f"*-{vcs[:10]}-*.txt"))]
        # cesu8 preserves copyright and the Apache/MIT declaration in source
        # and COPYRIGHT-RUST.txt, but omits both standard license texts from
        # its published archive. Select Apache-2.0 and include the full terms.
        has_terms = any("LICENSE" in label.upper() or "COPYING" in label.upper() or "LICENCE" in label.upper() for label, _ in files)
        if files and not has_terms and "Apache-2.0" in (package.get("license") or ""):
            files.append(("Selected Apache-2.0 full terms (apache.org)", HERE / "licenses/apache-2.0-standard.txt"))
            directory = Path(package["manifest_path"]).parent
            source = directory / "src/lib.rs"
            if source.exists():
                # Preserve the actual project's copyright attribution too.
                preamble = source.read_text().split("\n\n", 1)[0].strip()
                attribution = HERE / "licenses" / f"{package['name']}-{package['version']}-SOURCE-COPYRIGHT.txt"
                attribution.write_text(preamble + "\n")
                files.append(("Upstream source copyright and license declaration", attribution))
        if not files or not any("LICENSE" in label.upper() or "COPYING" in label.upper() or "LICENCE" in label.upper() for label, _ in files):
            raise SystemExit(f"Missing license text for active dependency {package['name']} {package['version']}")
        record = [f"{package['name']} {package['version']}", f"Declared license: {package.get('license') or 'see license text'}"]
        if package.get("authors"):
            record.append("Attribution: " + "; ".join(package["authors"]))
        if package.get("repository"):
            record.append("Repository: " + package["repository"])
        if vcs:
            record.append("Source VCS commit: " + vcs)
        for label, path in files:
            record.extend(["", "--- " + label + " ---", path.read_text(errors="replace").strip()])
        records.append("\n".join(record))
    header = f"""LOCAL WRITER — HARPER THIRD-PARTY NOTICES

These notices cover the active Android ARM64 Rust runtime and code-generation
dependency graph from native/harper/Cargo.lock ({len(packages)} third-party packages).
Build-time dependencies are included for attribution; this is not a claim that
each package is individually linked into the final library. Optional unused
Cargo packages and unsupported-platform dependencies are excluded.

Harper 2.12.0 — Automattic Inc. and contributors
Upstream commit: {REVISION}
Repository: https://github.com/Automattic/harper
Copyright 2024 Automattic Inc. — Apache License 2.0

The bundled Harper Brill POS tagger, CPU Burn noun-phrase chunker model and
vocabulary are the upstream resources at this same commit:
harper-brill/trained_tagger_model.json
harper-brill/finished_chunker/model.mpk (806,312 bytes)
harper-brill/finished_chunker/vocab.json (627,993 bytes)
Their containing upstream Harper packages declare Apache-2.0; the Harper
license text is included below. Training corpora are not bundled in this APK.
Burn and its active CPU dependencies are attributed individually below.

Local compatibility modifications to three Harper rule source files add four
explicit predicate function-pointer casts for Rust 1.93 compilation. Predicate
and suggestion behavior is unchanged. Patch details are recorded in
native/harper/patches/rust193-predicate-coercion.json.

License texts and copyright/notice files below are preserved from downloaded
crate source archives, same-commit workspace packages, or pinned upstream
license files. Dual-license texts are both retained; inclusion does not add
restrictions beyond the license selected for the applicable component.
"""
    content = header + "\n\n" + ("\n\n" + "=" * 78 + "\n\n").join(records) + "\n"
    output = HERE / "THIRD_PARTY_NOTICES.txt"
    output.write_text(content)
    asset = ROOT / "app/src/main/assets/notices/HARPER-DEPENDENCIES.txt"
    asset.parent.mkdir(parents=True, exist_ok=True)
    asset.write_text(content)
    print(f"Collected licenses for {len(packages)} active dependencies: {output} ({output.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
