#!/usr/bin/env python3
from pathlib import Path
import os,subprocess
r=Path(__file__).resolve().parent.parent;os.chdir(r)
env=os.environ.copy();env['JAVA_OPTS']='-Xmx640m'
source=r/'app/src/main/kotlin/dev/localwriter'
cp='vendor/host/onnxruntime.jar:vendor/host/json.jar'
out=r/'build/engine-test.jar';out.parent.mkdir(exist_ok=True)
subprocess.run(['.cache/kotlinc/bin/kotlinc','-jvm-target','11','-classpath',cp,'-d',str(out),str(source/'TextLogic.kt'),str(source/'GrammarEngine.kt'),str(source/'SpellingEngine.kt'),'tests/EngineHost.kt'],check=True,env=env)
subprocess.run(['java','-Xmx512m','-cp',str(out)+':'+cp+':.cache/kotlinc/lib/kotlin-stdlib.jar','dev.localwriter.EngineHostKt','app/src/main/assets/local-model'],check=True)
