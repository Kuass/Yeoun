# ivLyrics feature parity

Compared with local ivLyrics source on 2026-09-08. The scoped desktop features below are implemented; this is not full desktop feature parity.

| Capability | Android behavior | Desktop reference |
| --- | --- | --- |
| Synced lyrics and karaoke | Existing line and syllable timing playback | Pages.js |
| Fullscreen, video, song explanations | Existing; fullscreen now scrolls when the selected ranges exceed the viewport | OptionsMenu.js |
| Local timing editor | Line timing; plain search results can be used as untimed drafts | OptionsMenu.js |
| LRC import/export | UTF-8, 512 KiB limit, validation before replacement; export includes line timing | OptionsMenu.js local lyrics tools |
| Per-song provider | Explicit source overrides global/community providers; local lyrics stay first | OptionsMenu.js track-lyrics-provider |
| Manual LRCLIB search | Query, metadata, timing type, preview, explicit application to the captured song | OptionsMenu.js performSearch / applyCandidate |
| User presets | Named save, replace, apply, delete; 20 presets, 40-character names | Settings.js |
| Translation/pronunciation editor | Choose a lyric line, edit either field, restore generated values | index.js cache-edit modal |
| Language-specific display | Translation and pronunciation chosen independently for each source language | OptionsMenu.js first-language prompt |
| Neighbouring extra lines | Separate previous/next ranges for original, translation, and pronunciation | Shared Android LyricsView |

The six subsequent reading/backup/offline improvements are documented in [Reading tools](READING_TOOLS.md).

## Language selection

**Translation → Language display settings** lists all supported source-language categories. Choose translation, pronunciation, both, or neither. Saving both switches off means original only; **Ask again** removes that explicit choice. Global translation/pronunciation switches temporarily hide their respective output for every language without deleting individual choices.

An unconfigured foreign language shows choices and a settings button below the floating lyrics. No generation runs for those lines before a choice. Lyrics already in the target language default to original only, unless an explicit rule says otherwise. Missing AI credentials open settings when generation is selected. No API key is required for offline detection or manual editing.

Detection is an offline heuristic based on scripts and common words, not a model-backed language identification service. It distinguishes Korean, English, Japanese, Chinese, Spanish, French, German, Portuguese, Vietnamese, Indonesian, Thai, and Russian; ambiguous and unsupported text is **Unknown language**. Mixed songs are classified per line. Short Latin hooks inherit a clearly dominant language among confidently detected Latin-script lines, including inside Korean-majority songs; competing Latin languages keep ambiguous text unknown. Single Hangul syllables and compatibility/decomposed Hangul are recognized after Unicode normalization. Punctuation-only and music-symbol lines do not trigger language prompts. When one language accounts for at least 80% of content lines (with at least three confident lines), short uncertain Latin-script inserts of up to eight tokens can follow that primary language if no stronger same-script evidence exists. Confident foreign-language lines, unsupported writing systems, long uncertain passages, and explicit track overrides are preserved. This is contextual display policy, not a claim that every word literally belongs to the primary language. Incorrect detection can be overridden under **Sources → Now playing → Source language for this song**.

## Display and spacing

**Display** has independent previous/next sliders for translations and pronunciation: 0–3 previous lines and 0–5 next lines. Zero means current line only. The original window does not cap either extra window. Original and pronunciation have no added vertical gap between them; translation and the next lyric group have a larger gap. The shared renderer applies this to preview, overlay, and fullscreen. Long floating content scrolls within a bounded lyric region so the language choices remain available.

## Search, files, and edits

Manual search uses the public [LRCLIB API](https://lrclib.net/docs). Preview a result before applying; results include song, artist, album, duration, and synchronized/plain distinction. Plain lyrics remain explicitly approximate after saving and do not become fabricated synchronized LRC. Applying replaces the selected song's local lyrics, even if playback changes during the dialog. Local removal restores online sources.

Line edits are stored in app-private persistent files, separately from generated AI cache. Empty fields intentionally hide that output. Restoring a line removes its corrections and uses cached/generated output again. Edits are keyed by track, complete original text, and translation options; they never transfer to another lyric version or target language. Current-language display choices still determine whether an edited field is visible. Existing request revision checks reject stale results after track, language, or settings changes.

## Preset scope

Presets include original/extra display ranges, size, background, animation, karaoke, pause hiding, global offset, target language, translation style, pronunciation notation, master display switches, and explicit language choices. Missing saved settings return to defaults on apply. API keys, service endpoints, models, extra prompt instructions, per-song settings, local lyrics, and corrections are excluded. Applying updates the settings screen and renderer without restarting Spotify.

## Verification

Validated on 2026-09-08: 51 JVM unit tests passed; four Android 36 emulator checks passed. The first overlay check was obscured by a System UI ANR from emulator startup; it passed after dismissing that system dialog. The overlay screenshot and device protocol log are in `build/quality-evidence/`. A public LRCLIB search returned 20 candidates in the live read-only check. No paid AI requests or real Spotify playback were used for these checks.

- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`
- Device checks use the platform instrumentation protocol without network services or AI credentials. On an isolated emulator, install both debug APKs, grant overlay permission, then run `adb shell am instrument -w dev.kuass.ivlyrics.test/dev.kuass.ivlyrics.QualityInstrumentation`.
- Device checks cover actual preferences/presets, renderer row order and spacing, local plain-vs-synced persistence, durable corrections, and a real overlay prompt screenshot.
- Live Spotify playback, third-party network availability, and paid AI output are separate integration boundaries. Syllable-level sync creation, community upload, and other desktop-only capabilities remain outside this scope.
