# Reading tools

Implemented in the proposed order on 2026-09-08.

| Feature | Entry and behavior |
| --- | --- |
| Word-associated pronunciation | Display → Pronunciation beneath each word. Original tokens and matching pronunciation chunks wrap as one unit. Original text and karaoke character indices remain intact. |
| Generation progress and retry | Sources → Now playing. Each channel shows hidden, awaiting a language choice, missing AI setup, generating, ready, or failed with completed/total counts. Retry sends only missing enabled lines. The floating window only offers a retry button on failure; diagnostic-looking progress counters are not shown there. |
| Floating quick controls | Drag the floating window to briefly reveal Quick controls, or long-press the lyrics. The launcher and controls hide five seconds after the last interaction. Toggle the global translation/pronunciation switches; adjust this song's offset by ±100 ms or reset it. Per-language choices remain saved. |
| Manual reading and follow | Drag within overflowing lyrics to pause automatic tracking. The displayed window stays fixed while reading. Current lyrics resumes immediately; otherwise tracking resumes three seconds after touch release. Available in fullscreen and the scrolling overlay. |
| Backup and restore | App settings → Export settings and personal lyrics / Restore from backup. A validated JSON preview shows counts before merging. |
| Recent/offline lyrics | Sources → Recent songs / offline lyrics. Open original lyrics, cached supplements and saved corrections without changing Spotify playback. Edit line supplements or remove a history entry. |

## Floating window presentation

Verified with 62 JVM tests and nine emulator checks. Device checks inject a drag, operate the transient sync controls, verify automatic hiding after five seconds, and sample intermediate heights during both growth and shrink. The previous progress text was ordinary release UI, not a debug-only label; it has been removed from the floating view in every build variant.

Normal playback shows no persistent Quick controls button or progress text. Moving the window reveals controls temporarily; interacting extends the five-second timeout. Opening another song or hiding the window clears the transient controls. Lyric-driven height changes interpolate over 350 ms from the currently displayed height, including changes arriving mid-transition. System reduced-motion settings and the animation preference are respected.

## Pronunciation alignment

New AI pronunciation requests ask for one `｜`-separated chunk per original whitespace-delimited token. Legacy space-delimited readings also align when token counts match. Empty, mismatched, joining-script, or over-wide groups fall back to the separate pronunciation row; no original words are guessed or dropped. Unsegmented Japanese or Chinese lines may consequently retain a separate reading. Translation stays beneath the original/pronunciation group. Switching the setting off restores line-based pronunciation.

## Retry behavior

Language settings and master switches determine which lines can be generated. Manual corrections, including intentionally empty corrections, count as completed. Successful translation output is cached before pronunciation starts, so a later failure does not discard it. Retry merges missing results into cached data. Provider-load revisions are separate from enrichment revisions: changing a display language does not incorrectly cancel a pending fresh-original lookup, and an older cached translation cannot replace a newer original version.

## Backup contract

The backup contains approved display settings, source toggles, app language, per-language choices, per-song overrides, local LRC/plain lyrics, named presets, and durable line corrections. API keys, AI endpoints/models, YouTube API keys, extra AI instructions, generated-result caches, and recent-song archives are excluded.

Only schema version 1 (`YeounBackup`) is accepted; the input limit is 8 MiB. The complete payload is validated before writing, including LRC content, preset values and correction-file keys. Restoring merges matching entries while preserving unrelated data and existing credentials. More than 20 combined presets is rejected. Storage failure during the multi-store write can leave a partial restore; the UI reports this and the same backup can be retried after storage recovery. The selected JSON file is ordinary plaintext, suitable for the user's own storage provider.

## Offline originals

Up to 100 songs or 20 MiB of original lyrics are retained in app-private files. Source identity, synchronized/plain distinction, line timing and syllable timing survive persistence. Automatic playback first shows a compatible stored original and refreshes from enabled providers. A failed lookup retains that compatible original. Disabled sources and removed local overrides are not resurrected from history. Reading a recent song does not contact an AI provider; only already-cached or manually entered supplements can appear offline. Removing a history entry does not delete local lyrics or personal corrections, and playing the song again can store it again.

## Verification

A subsequent phone startup failure exposed an Activity-only test assumption: `NotificationListenerService` has no Material theme, so constructing a floating `MaterialButton` crashed the process. `LyricsOverlay` now creates its own explicitly themed context for every view. The added regression first verifies that a clean platform-theme context rejects a Material button, then verifies that the overlay can initialize and show from that same context. All eight emulator checks passed after the fix. The patched APK was reinstalled on the connected phone with data preserved; repeated app startup and the bound notification listener stayed alive with no new AndroidRuntime crash.

Final validation: 57 JVM tests passed, seven Android 36 emulator checks passed, debug APK build succeeded, and lint reported zero errors with 34 warnings. Evidence is in `build/quality-evidence/reading-device-checks.txt`, `reading-final-build.txt`, and `inline-overlay.png`.

JVM tests cover alignment/fallback, scroll grace periods, generation states, backup validation and credential exclusion, cache-source eligibility, and complete offline timing round trips. Android instrumentation checks actual wrapped spans and karaoke state, shared preference restoration, retained unrelated data, and offline file persistence in addition to the previous renderer/language tests.

Run `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`. Install the two debug APKs on an isolated emulator, grant overlay permission, and run `adb shell am instrument -w dev.kuass.ivlyrics.test/dev.kuass.ivlyrics.QualityInstrumentation`.

This validation does not include live Spotify playback or paid AI calls. AI output can fail the alignment contract and deliberately falls back to a separate reading line.
