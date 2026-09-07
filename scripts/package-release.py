#!/usr/bin/env python3
"""Validate the release version/signature and package only public release assets."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    args = parser.parse_args()
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", args.tag):
        parser.error("Use a stable version tag such as v0.3.0")
    version = args.tag[1:]
    script = Path("app/build.gradle.kts").read_text()
    if re.search(r'versionName\s*=\s*"([^"]+)"', script).group(1) != version:
        raise SystemExit("Release tag does not match versionName")
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    tools = sdk / "build-tools/35.0.0"
    apk = Path("app/build/outputs/apk/release/app-release.apk")
    verification = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    if "CN=Android Debug" in verification:
        raise SystemExit("Refusing to publish a debug-signed APK")
    metadata = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
    if "application-debuggable" in metadata or f"versionName='{version}'" not in metadata:
        raise SystemExit("APK is debuggable or has the wrong version")
    if "name='dev.kuass.ivlyrics'" not in metadata.splitlines()[0]:
        raise SystemExit("Unexpected application ID")
    output = Path("build/release-assets")
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    shutil.copy2(apk, output / f"Yeoun-{version}.apk")
    (output / "signing-certificate.txt").write_text(verification)
    for file in ["LICENSE", "THIRD_PARTY_NOTICES.md"]:
        shutil.copy2(file, output / file)
    subprocess.run(["git", "archive", "--format=zip", f"--prefix=Yeoun-{version}/", f"--output={output}/Yeoun-{version}-source.zip", "HEAD"], check=True)
    checks = [f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}" for path in sorted(output.iterdir())]
    (output / "SHA256SUMS.txt").write_text("\n".join(checks) + "\n")
    print(f"Verified signed, non-debuggable release {args.tag}: {output}")


if __name__ == "__main__":
    main()
