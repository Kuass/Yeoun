#!/usr/bin/env python3
"""Install a pinned local scanner from upstream; verify the archive before extracting."""
import argparse
import hashlib
import io
import platform
from pathlib import Path
import tarfile
import urllib.request

VERSION = "8.30.1"
CHECKSUMS = {
    "darwin_arm64": "b40ab0ae55c505963e365f271a8d3846efbc170aa17f2607f13df610a9aeb6a5",
    "darwin_x64": "dfe101a4db2255fc85120ac7f3d25e4342c3c20cf749f2c20a18081af1952709",
    "linux_arm64": "e4a487ee7ccd7d3a7f7ec08657610aa3606637dab924210b3aee62570fb4b080",
    "linux_x64": "551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("build/tools/gitleaks"))
    args = parser.parse_args()
    arch = {"aarch64": "arm64", "arm64": "arm64", "x86_64": "x64", "AMD64": "x64"}.get(platform.machine())
    target = f"{platform.system().lower()}_{arch}"
    if target not in CHECKSUMS:
        parser.error("Unsupported platform; install Gitleaks 8.30.1 and pass its path to the check script.")
    url = f"https://github.com/gitleaks/gitleaks/releases/download/v{VERSION}/gitleaks_{VERSION}_{target}.tar.gz"
    with urllib.request.urlopen(url, timeout=60) as response:
        payload = response.read()
    if hashlib.sha256(payload).hexdigest() != CHECKSUMS[target]:
        raise SystemExit("Gitleaks checksum mismatch")
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:gz") as archive:
        member = archive.getmember("gitleaks")
        if not member.isfile():
            raise SystemExit("Invalid Gitleaks archive")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(archive.extractfile(member).read())
    args.output.chmod(0o755)
    print(f"Installed Gitleaks {VERSION}: {args.output}")


if __name__ == "__main__":
    main()
