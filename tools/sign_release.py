#!/usr/bin/env python3
"""Sign a built release using the existing, private fork identity; never generate a replacement key."""
import json
import os
import re
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
private = root / ".private"
sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
tool = sdk / "build-tools/37.0.0/apksigner"
env = os.environ.copy()
env["SEFIRAH_SIGNING_PASSWORD"] = json.loads((private / "release-signing.json").read_text())["password"]
if not env.get("JAVA_HOME") and Path("/usr/libexec/java_home").exists():
    env["JAVA_HOME"] = subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip()
version = re.search(r'versionName\s*=\s*"([^"]+)"', (root / "app/build.gradle.kts").read_text()).group(1)
output = root / "dist" / f"Sefirah-Android-{version}.apk"
output.parent.mkdir(exist_ok=True)
subprocess.run([str(tool), "sign", "--ks", str(private / "Sefirah-release.p12"),
    "--ks-key-alias", "sefirah", "--ks-pass", "env:SEFIRAH_SIGNING_PASSWORD",
    "--out", str(output), str(root / "app/build/outputs/apk/release/app-release-unsigned.apk")],
    env=env, check=True)
subprocess.run([str(tool), "verify", str(output)], env=env, check=True)
print(f"Verified release: {output}")
