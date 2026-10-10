#!/usr/bin/env python3
"""Small-memory Kotlin/SDK build; no Gradle daemon or Android emulator required."""
from pathlib import Path
import os, subprocess, shutil, zipfile, hashlib, json, urllib.request, sys
ROOT = Path(__file__).resolve().parent.parent
os.chdir(ROOT)
SDK = Path(os.environ.get('ANDROID_SDK_ROOT', str(Path.home()/'Android/Sdk')))
BT = SDK/'build-tools/36.0.0'
ANDROID = SDK/'platforms/android-36/android.jar'
NDK = SDK/'ndk/27.1.12297006'
BUILD = ROOT/'build'; BUILD.mkdir(exist_ok=True)

def run(*args):
    print('Running:',str(args[0]),flush=True)
    subprocess.run(list(map(str,args)),check=True)

def digest(path):
    h=hashlib.sha256()
    with open(path,'rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return h.hexdigest()

kotlinc = ROOT/'.cache/kotlinc/bin/kotlinc'
if not kotlinc.exists():
    archive=ROOT/'.cache/kotlin-compiler-2.2.20.zip'; archive.parent.mkdir(exist_ok=True)
    urllib.request.urlretrieve('https://github.com/JetBrains/kotlin/releases/download/v2.2.20/kotlin-compiler-2.2.20.zip',archive)
    with zipfile.ZipFile(archive) as z:z.extractall(archive.parent)
    kotlinc.chmod(0o755)
if not (ROOT/'vendor/llama.cpp/CMakeLists.txt').exists():
    raise SystemExit('Prepare dependencies with python3 tools/bootstrap.py first.')
run('cmake','-S','native','-B','build/native','-DCMAKE_TOOLCHAIN_FILE='+str(NDK/'build/cmake/android.toolchain.cmake'),'-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-33','-DANDROID_STL=c++_static','-DCMAKE_BUILD_TYPE=Release','-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON','-DGGML_CPU_ARM_ARCH=armv8-a')
run('cmake','--build','build/native','--target','localrewrite','-j','2')
run(BT/'aapt2','compile','--dir','app/src/main/res','-o',BUILD/'resources.zip')
generated=BUILD/'generated';generated.mkdir(exist_ok=True)
run(BT/'aapt2','link','-I',ANDROID,'--manifest','app/src/main/AndroidManifest.xml','--java',generated,'--min-sdk-version','33','--target-sdk-version','36','--version-code','2','--version-name','0.1.1','-o',BUILD/'resources.apk',BUILD/'resources.zip')
classes=BUILD/'classes'
if classes.exists():shutil.rmtree(classes)
classes.mkdir()
run('javac','--release','11','-cp',ANDROID,'-d',classes,*generated.rglob('*.java'))
env=os.environ.copy();env['JAVA_OPTS']='-Xmx640m'
cmd=[str(kotlinc),'-jvm-target','11','-no-reflect','-classpath',str(ANDROID)+os.pathsep+str(ROOT/'vendor/onnxruntime.jar')+os.pathsep+str(classes),'-d',str(classes),*map(str,sorted((ROOT/'app/src/main/kotlin').rglob('*.kt')))]
subprocess.run(cmd,check=True,env=env)
run('jar','cf',BUILD/'app.jar','-C',classes,'.')
dex=BUILD/'dex';dex.mkdir(exist_ok=True)
libs=ROOT/'.cache/kotlinc/lib'
stdlib=[libs/'kotlin-stdlib.jar',libs/'kotlin-stdlib-jdk7.jar',libs/'kotlin-stdlib-jdk8.jar']
run(BT/'d8','--min-api','33','--lib',ANDROID,'--output',dex,BUILD/'app.jar','vendor/onnxruntime.jar',*stdlib)
unsigned=BUILD/'local-writer-unsigned.apk'
with zipfile.ZipFile(unsigned,'w',allowZip64=False) as z:
    with zipfile.ZipFile(BUILD/'resources.apk') as resources:
        for entry in resources.infolist():z.writestr(entry,resources.read(entry))
    for f in dex.glob('*.dex'):z.write(f,f.name,compress_type=zipfile.ZIP_DEFLATED)
    for f in (ROOT/'app/src/main/assets').rglob('*'):
        if f.is_file():z.write(f,'assets/'+str(f.relative_to(ROOT/'app/src/main/assets')),compress_type=zipfile.ZIP_STORED)
    with zipfile.ZipFile(ROOT/'vendor/onnxruntime-android-1.23.2.aar') as aar:
        for name in ['libonnxruntime.so','libonnxruntime4j_jni.so']:
            z.writestr('lib/arm64-v8a/'+name,aar.read('jni/arm64-v8a/'+name),compress_type=zipfile.ZIP_STORED)
    z.write(BUILD/'native/liblocalrewrite.so','lib/arm64-v8a/liblocalrewrite.so',compress_type=zipfile.ZIP_STORED)
run(BT/'zipalign','-f','-P','16','4',unsigned,BUILD/'local-writer-aligned.apk')
key=BUILD/'signing/local-writer.p12';key.parent.mkdir(exist_ok=True)
if not key.exists():
    run('keytool','-genkeypair','-keystore',key,'-storetype','PKCS12','-alias','local','-storepass','local-development','-keypass','local-development','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=Local Writer Personal Build')
    key.chmod(0o600)
dist=ROOT/'dist';dist.mkdir(exist_ok=True);apk=dist/'local-writer-arm64.apk'
run(BT/'apksigner','sign','--ks',key,'--ks-key-alias','local','--ks-pass','pass:local-development','--key-pass','pass:local-development','--out',apk,BUILD/'local-writer-aligned.apk')
run(BT/'apksigner','verify','--verbose',apk)
run(sys.executable,'tools/verify.py',apk)
(dist/'SHA256SUMS').write_text(digest(apk)+'  '+apk.name+'\n')
print('Built',apk,apk.stat().st_size,'bytes')
