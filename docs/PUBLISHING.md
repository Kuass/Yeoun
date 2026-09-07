# Publishing Yeoun

[English README](../README.md) · [한국어 README](../README.ko.md)

## Prepared source package

The public source package includes the application, tests, Gradle wrapper, LGPL-2.1 license, bundled-font notices, bilingual documentation, reviewed screenshots, GitHub issue templates, and the Quality workflow. The demo text was written for Yeoun. Original design drafts, device recordings, machine-specific SDK paths, build outputs and signing material are excluded.

## Verify exactly what will be published

Stage the intended files explicitly, then run:

```bash
python3 scripts/install-gitleaks.py
python3 scripts/check-publication.py --build
```

Use JDK 21 for Gradle, have JDK 17 available for the Kotlin toolchain, and set `ANDROID_HOME`. The check exports the Git index to a fresh temporary folder. It rejects local-only paths, validates documentation links and license copies, runs Gitleaks locally with redacted output, and builds and tests that exact snapshot. It also rejects an index that changes while verification is running.

Results are in `build/publication/result.json`, including the Git tree ID and debug APK checksum. The verified APK is `build/publication/yeoun-debug.apk`. Neither file is source-controlled. Re-run the check after changing staged content.

The GitHub workflow runs the same check on pushes and pull requests. Workflow dependencies are pinned by commit hash; Gitleaks and the Gradle distribution are checked against pinned SHA-256 values. The workflow needs no third-party audit account and sends no source to a secret-scanning service.

## Source publication

Commit the verified tree, then publish that commit to the intended remote. A repository URL, CI run link, or download badge must be added only after the corresponding remote resource exists. The source can be published independently of an APK release.

## Automated signed releases

The [Release workflow](../.github/workflows/release.yml) runs when a stable `vX.Y.Z` tag is pushed. It runs the Quality workflow first, verifies that the tag matches the checked-out commit on `main`, then builds and tests the release variant. `scripts/package-release.py` rejects unsigned, debug-signed, debuggable, wrong-package, and wrong-version APKs. Assets are uploaded to a draft release before it is published.

Required GitHub Actions secrets: `YEOUN_KEYSTORE_BASE64`, `YEOUN_KEYSTORE_PASSWORD`, `YEOUN_KEY_ALIAS`, and `YEOUN_KEY_PASSWORD`. Keep the original release key and credentials backed up securely outside Git; changing the key breaks in-place updates for users.

For each release, update `versionName` and increase `versionCode` in `app/build.gradle.kts`, add bilingual notes in `docs/releases/vX.Y.Z.md`, pass Quality on `main`, then push the matching tag. Published release assets should not be overwritten. Manual dispatch is also supported: select the existing tag as the workflow ref and provide the same tag as input.

For a local signed build, export `YEOUN_KEYSTORE_PATH`, `YEOUN_KEYSTORE_PASSWORD`, `YEOUN_KEY_ALIAS`, and `YEOUN_KEY_PASSWORD`, then run:

```bash
./gradlew --no-configuration-cache -PrequireReleaseSigning=true testReleaseUnitTest lintRelease assembleRelease
python3 scripts/package-release.py v0.3.0
```

## Binary release verification

The verification artifact is explicitly a debug build. Keep a release signing key outside the repository, configure Android release signing, and verify the signed build before distributing it to users. Ship the matching source and build instructions alongside binary releases, retaining the license and third-party notices. Keep version numbers and release tags aligned with the published source.

## Verification boundaries

Local verification covers source packaging, secret-pattern detection, unit tests, lint, and the debug build. Device captures cover the connected phone and both UI languages. This is not a claim that every Android device, every accessibility configuration, or every live lyrics provider has been tested. GitHub-hosted execution can only be confirmed after uploading the repository.
