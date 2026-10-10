#!/usr/bin/env python3
"""Restore pinned build inputs. Downloads are build-time only, never in the app."""
from pathlib import Path
import argparse, hashlib, json, shutil, tarfile, urllib.request, zipfile

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--inputs', type=Path, help='Optional supplied project directory; reuse matching files before downloading')
parser.add_argument('--host-tests', action='store_true', help='Also prepare desktop ONNX/JSON jars for engine tests')
args = parser.parse_args()
lock = json.loads((ROOT/'dependencies.lock.json').read_text())

def matches(path, entry):
    if not path.is_file() or path.stat().st_size != entry['bytes']: return False
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest() == entry['sha256']

for entry in lock['files']:
    if entry['path'].startswith('vendor/host/') and not args.host_tests: continue
    dest = ROOT/entry['path']
    if matches(dest, entry): continue
    dest.parent.mkdir(parents=True, exist_ok=True)
    temp = dest.with_suffix(dest.suffix+'.part')
    supplied = args.inputs/entry['source'] if args.inputs and entry.get('source') else None
    if supplied and matches(supplied, entry):
        shutil.copyfile(supplied, temp)
    else:
        print('Downloading', entry['path'], flush=True)
        with urllib.request.urlopen(entry['url'], timeout=120) as source, temp.open('wb') as target:
            shutil.copyfileobj(source, target, 1024*1024)
    if not matches(temp, entry):
        temp.unlink(missing_ok=True)
        raise SystemExit('SHA-256 mismatch: '+entry['path'])
    temp.replace(dest)

with zipfile.ZipFile(ROOT/'vendor/onnxruntime-android-1.23.2.aar') as aar:
    (ROOT/'vendor/onnxruntime.jar').write_bytes(aar.read('classes.jar'))

llama = ROOT/'vendor/llama.cpp'
if not (llama/'CMakeLists.txt').exists():
    revision = lock['llama_revision']
    archive = ROOT/'.cache/llama-source.tar.gz'
    archive.parent.mkdir(exist_ok=True)
    print('Downloading llama.cpp at', revision, flush=True)
    urllib.request.urlretrieve('https://codeload.github.com/ggml-org/llama.cpp/tar.gz/'+revision, archive)
    unpack = ROOT/'.cache/llama-unpacked'
    unpack.mkdir(exist_ok=True)
    with tarfile.open(archive) as source: source.extractall(unpack, filter='data')
    shutil.move(str(unpack/('llama.cpp-'+revision)), llama)
print('Build inputs ready. No Grammarly APK is needed.')
