#!/usr/bin/env python3
"""Check the exact Git index, not a potentially different working directory."""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET


def run(*args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gitleaks", type=Path, default=Path("build/tools/gitleaks"))
    parser.add_argument("--build", action="store_true", help="Build and test the extracted index snapshot")
    args = parser.parse_args()
    repo = Path(subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip())
    os.chdir(repo)
    scanner = args.gitleaks.resolve()
    if not scanner.is_file():
        parser.error("Run python3 scripts/install-gitleaks.py first, or pass --gitleaks.")
    unstaged = subprocess.check_output(["git", "diff", "--name-only"], text=True).strip()
    untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"], text=True).strip()
    if unstaged or untracked:
        raise SystemExit("Working files differ from the publication index; stage reviewed changes or explicitly ignore local files before checking.")
    tree = subprocess.check_output(["git", "write-tree"], text=True).strip()
    entries = subprocess.check_output(["git", "ls-files", "-z"]).decode().split("\0")
    paths = [Path(name) for name in entries if name]
    forbidden = []
    for path in paths:
        if path.parts[0] in {"design", ".gradle", ".kotlin", ".idea", "build"} or "build" in path.parts:
            forbidden.append(str(path))
        elif path.name in {"local.properties", "keystore.properties", "signing.properties", ".DS_Store"}:
            forbidden.append(str(path))
        elif path.name.startswith(".env") and path.name != ".env.example":
            forbidden.append(str(path))
        elif path.suffix in {".jks", ".keystore", ".p12", ".pem", ".apk", ".aab", ".mp4"}:
            forbidden.append(str(path))
    if forbidden:
        raise SystemExit("Excluded files are still indexed: " + ", ".join(forbidden))
    report_dir = repo / "build/publication"
    report_dir.mkdir(parents=True, exist_ok=True)
    report = {"tree": tree, "files": len(paths)}
    with tempfile.TemporaryDirectory(prefix="yeoun-publication-") as temp:
        snapshot = Path(temp)
        run("git", "checkout-index", "--all", "--prefix=" + str(snapshot) + os.sep)
        if any(path.is_symlink() for path in snapshot.rglob("*")):
            raise SystemExit("Review symbolic links before publication")
        for name in ["README.md", "README.ko.md", "THIRD_PARTY_NOTICES.md", "docs/PUBLISHING.md"]:
            text = (snapshot / name).read_text()
            if re.search(r"/Users/[A-Za-z0-9._-]+/|/home/[A-Za-z0-9._-]+/", text):
                raise SystemExit(f"Personal path in indexed {name}")
            for markdown, html in re.findall(r'\]\(([^)]+)\)|src="([^"]+)"', text):
                link = markdown or html
                if link.startswith(("https://", "http://", "#", "mailto:")):
                    continue
                if not (snapshot / name).parent.joinpath(link.split("#")[0]).exists():
                    raise SystemExit(f"Broken relative link in {name}: {link}")
        license_bytes = (snapshot / "LICENSE").read_bytes()
        if license_bytes != (snapshot / "app/src/main/assets/licenses/Yeoun-LGPL-2.1.txt").read_bytes():
            raise SystemExit("Repository and APK license texts differ")
        run(str(scanner), "dir", str(snapshot), "--redact", "--no-banner",
            "--report-format", "json", "--report-path", str(report_dir / "gitleaks.json"))
        report["secret_scan"] = "passed"
        if args.build:
            wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
            run(wrapper, "--no-configuration-cache", "testDebugUnitTest", "lintDebug", "assembleDebug", cwd=snapshot)
            roots = [ET.parse(path).getroot() for path in (snapshot / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml")]
            if not roots:
                raise SystemExit("Build produced no unit-test results")
            report["tests"] = {key: sum(int(root.get(key, 0)) for root in roots) for key in ["tests", "failures", "errors", "skipped"]}
            issues = list(ET.parse(snapshot / "app/build/reports/lint-results-debug.xml").getroot())
            report["lint"] = dict(Counter(issue.get("severity") for issue in issues))
            apk = snapshot / "app/build/outputs/apk/debug/app-debug.apk"
            report["debug_apk_sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
            shutil.copy2(apk, report_dir / "yeoun-debug.apk")
            shutil.copytree(snapshot / "app/build/test-results/testDebugUnitTest", report_dir / "tests", dirs_exist_ok=True)
            shutil.copy2(snapshot / "app/build/reports/lint-results-debug.xml", report_dir / "lint.xml")
        if subprocess.check_output(["git", "diff", "--name-only"], text=True).strip():
            raise SystemExit("Working files changed during verification; review and rerun the check")
        if tree != subprocess.check_output(["git", "write-tree"], text=True).strip():
            raise SystemExit("The Git index changed during verification; rerun the check")
    (report_dir / "result.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
