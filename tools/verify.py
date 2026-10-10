#!/usr/bin/env python3
from pathlib import Path
import zipfile, subprocess, os, sys, hashlib, json
r=Path(__file__).resolve().parent.parent
sdk=Path(os.environ.get('ANDROID_SDK_ROOT',str(Path.home()/'Android/Sdk')))
a=Path(sys.argv[1])
p=subprocess.check_output([str(sdk/'build-tools/36.0.0/aapt'),'dump','permissions',str(a)],text=True)
assert "package: dev.localwriter" in p
for forbidden in ['INTERNET','ACCESS_NETWORK_STATE','AD_ID','BILLING','CHECK_LICENSE']:assert forbidden not in p
m=subprocess.check_output([str(sdk/'build-tools/36.0.0/aapt'),'dump','xmltree',str(a),'AndroidManifest.xml'],text=True)
for name in ['MainActivity','WriterAccessibilityService','WriterKeyboard','BIND_ACCESSIBILITY_SERVICE','BIND_INPUT_METHOD']:assert name in m
for forbidden in ['pairip','Firebase','LicenseActivity','grammarly']:assert forbidden not in m
with zipfile.ZipFile(a) as z:
    assert not any('pairip' in n for n in z.namelist())
    for name in ['liblocalrewrite.so','liblocalharper.so','libonnxruntime.so','libonnxruntime4j_jni.so']:assert z.getinfo('lib/arm64-v8a/'+name).file_size>50000
    harper=json.loads((r/'build/harper/android/build-info.json').read_text())
    assert hashlib.sha256(z.read('lib/arm64-v8a/liblocalharper.so')).hexdigest() == harper['sha256']
    for notice in (r/'app/src/main/assets/notices').iterdir():
        if notice.is_file(): assert z.read('assets/notices/'+notice.name) == notice.read_bytes(),notice.name
    for f in (r/'app/src/main/assets/local-model').iterdir():
        h=hashlib.sha256(); expected=hashlib.sha256()
        with z.open('assets/local-model/'+f.name) as stream:
            for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
        with f.open('rb') as stream:
            for chunk in iter(lambda:stream.read(1024*1024),b''):expected.update(chunk)
        assert h.digest()==expected.digest(),f.name
        if f.name == 'context-t5-small-q8.gguf':
            lock=json.loads((r/'context-model.lock.json').read_text())['asset']
            assert z.getinfo('assets/local-model/'+f.name).file_size == lock['bytes']
            assert h.hexdigest() == lock['sha256']
    assert z.read('classes.dex').startswith(b'dex\n')
subprocess.run([str(sdk/'build-tools/36.0.0/zipalign'),'-c','-P','16','4',str(a)],check=True)
print('Verified independent package, offline permissions, accessibility/IME, model hashes, native libraries and alignment.')
