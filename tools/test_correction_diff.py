#!/usr/bin/env python3
"""Focused correction regression checks without a model, phone or emulator."""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parent.parent
os.chdir(root)
output = root / 'build/correction-diff-test.jar'
output.parent.mkdir(exist_ok=True)
env = os.environ.copy()
env['JAVA_OPTS'] = '-Xmx384m'
subprocess.run([
    '.cache/kotlinc/bin/kotlinc', '-jvm-target', '11', '-d', str(output),
    'app/src/main/kotlin/dev/localwriter/TextLogic.kt',
    'app/src/main/kotlin/dev/localwriter/IssueStore.kt',
    'app/src/main/kotlin/dev/localwriter/WritingCheckState.kt', 'tests/CorrectionDiffHost.kt'
], check=True, env=env)
subprocess.run([
    'java', '-Xmx128m', '-cp', str(output) + ':.cache/kotlinc/lib/kotlin-stdlib.jar',
    'dev.localwriter.CorrectionDiffHostKt'
], check=True)
