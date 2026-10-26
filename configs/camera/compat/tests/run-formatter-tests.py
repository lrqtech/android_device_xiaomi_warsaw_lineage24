#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the source formatter against this checkout's org.json with host Java tools."""
from pathlib import Path
import argparse
import subprocess
import tempfile

here = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--android-root", type=Path, default=here.parents[6])
args = parser.parse_args()
root = args.android_root.resolve()
jdk = root / "prebuilts/jdk/jdk21/linux-x86/bin"
sources = sorted((root / "libcore/json/src/main/java/org/json").glob("*.java"))
if not sources:
    parser.error("Android org.json sources are missing")
sources += [
    root / "frameworks/libs/modules-utils/java/android/annotation/SystemApi.java",
    root / "tools/platform-compat/java/android/compat/annotation/UnsupportedAppUsage.java",
    root / "libcore/luni/src/main/java/libcore/util/NonNull.java",
    root / "libcore/luni/src/main/java/libcore/util/Nullable.java",
    root / "libcore/luni/src/main/java/libcore/api/IntraCoreApi.java",
    here.parent / "src/com/miui/cameraopt/quickcamera/CameraMindEventFormatter.java",
    here / "CameraMindEventFormatterTest.java",
]
# Only temporary .class files are produced; no jars, APKs, images or archives.
with tempfile.TemporaryDirectory(prefix="warsaw-formatter-test-") as output:
    subprocess.run([str(jdk / "javac"), "-proc:none", "-d", output,
                    *map(str, sources)], check=True)
    subprocess.run([str(jdk / "java"), "-cp", output,
                    "com.miui.cameraopt.quickcamera.CameraMindEventFormatterTest"], check=True)
