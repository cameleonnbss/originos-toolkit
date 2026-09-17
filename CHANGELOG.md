# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.1] — 2026-09-17

A correctness release. The headline: **the toolkit no longer needs Shizuku for everything**,
and two things that claimed to work did not.

### Added

- **A real no-Shizuku path.** 8 of the 39 tweaks only touch the `system` settings namespace,
  which an ordinary app may write with the *modify system settings* special access. They are
  now executed in-process by `SettingsShell` through the app's own `ContentResolver` — no
  Shizuku, no root, no computer, no background process. This includes
  `force-max-refresh-rate`, the reason most people install the app.
- `requires: "settings"` as a fourth access level in the catalog, plus a lint rule that
  rejects a declaration its own actions contradict. The catalog previously declared all 39
  tweaks as needing Shizuku, which was false for these 8.
- The engine now checks access for the whole tweak *before* executing any of it. A tweak
  mixing a `system` write with a `global` one is refused outright instead of half applying
  and leaving the journal holding an inverse it can never run.
- The Android app learns the difference: a *Works without Shizuku* filter, a `NO SHIZUKU`
  badge computed from the actions, per-tweak guidance instead of a global "Shizuku is off"
  warning, and an **Access** card that offers the settings grant first.
- `originos-toolkit catalog --no-shizuku`, a `withoutShizuku` field in `--json`, an `access`
  line in `show`, and a no-Shizuku count in `doctor`.
- `scripts/check_links.py`, run in CI: every published link is resolved, and a repository that
  has been renamed is reported so the link can be updated. A curated list with dead links is
  worse than no list.
- `scripts/check_links.py` is also honest about its own limits: an unverified link (API rate
  limit) is reported as an incomplete check rather than folding into a passing run.

### Changed

- **The FPS overlay no longer claims to measure frames when it measures vsync.** It now reads
  `SurfaceFlinger`'s present timestamps for the app in front — resolved to that app's own layer
  — and reports the real frame rate plus a **1% low**. Without the shell user it shows the
  panel refresh rate by name. The vsync figure, when shown, is a median of measured intervals
  rather than a frame count divided by a sampling window.
- `dumpsys SurfaceFlinger --latency` sampling without a layer name — which returns an arbitrary
  system layer — is gone from the dashboard, replaced by the same real measurement.
- Device detection no longer needs a shell. Model, brand, density, resolution and refresh-rate
  settings come from the app's own APIs; the Vivo-specific `ro.vivo.os.version` is reported as
  *unreadable without Shizuku* rather than guessed at.
- The per-app refresh watcher works without Shizuku too: it writes a `system` namespace key, so
  the settings grant is now sufficient (previously it insisted on Shizuku).
- CI: the CLI smoke test can no longer pass by printing nothing — `set -o pipefail` plus an
  explicit non-empty-output check, because `| head` was masking failures.

### Fixed

- **`python -m originos_toolkit.cli <anything>` did nothing.** The module had no
  `__main__` block, so it exited 0 and printed nothing — which means the Makefile targets
  (`make lint`, `make catalog`, `make doctor`) and the CI smoke test were checking nothing at
  all while looking green. `cli/tests/test_entrypoints.py` now spawns the real process so this
  cannot regress silently again.
- Two dead links in the curated index: `zacharee/SystemUITuner` (now
  `zacharee/SystemUITunerRedesign`) and `0x192/universal-android-debloater-next-generation`
  (now under `Universal-Debloater-Alliance`). Renamed repositories are updated to their
  canonical slugs, and the link checker skips code fences so schema examples are not mistaken
  for links.
- `export -o <file>` no longer depends on `Path.write_text(newline=...)`, which only exists
  on Python 3.10 and up. The generated script is now written as bytes, so the `LF` of a `.sh`
  export and the `CRLF` of a `.ps1` export survive verbatim on every platform.
- `WRITE_SETTINGS` is declared in the manifest and documented in `docs/PERMISSIONS.md`, with
  the narrowing spelled out: it reaches `system` and nothing else, and `WRITE_SECURE_SETTINGS`
  is still not requested.

[1.0.1]: https://github.com/cameleonnbss/originos-toolkit/releases/tag/v1.0.1

## [1.0.0] — 2026-09-16

First public release. No-root tweaking for Vivo / iQOO OriginOS devices, built around one
rule: *read the previous value, journal the inverse, and only then write.*

### Added

**Android app** (`dev.cameleonnbss.originostoolkit`)

- Per-app refresh rate: a foreground watcher that polls the foreground app and pins
  `peak_refresh_rate` / `min_refresh_rate` to a rate you choose per app, restoring the
  originals when you leave it.
- A floating frame-rate overlay driven by `Choreographer`, plus a manual
  `dumpsys SurfaceFlinger --latency` sample for real compositor timings.
- Compose UI with six sections: Home, Tweaks, Refresh, Debloat, Discover and Settings.
- Debloat screen using `pm disable-user --user 0` only — never uninstall — with live
  installed/disabled state.
- Discover screen shipping the curated no-root index (34 tools, labelled by access level).
- Revert journal with per-tweak revert, revert-all, and a panic reset that restores factory
  display metrics before replaying the journal.
- English and French string resources.

**Tweak catalog** (`catalog/`)

- 39 tweaks across 8 categories: display, performance, battery, privacy, debloat, input,
  system and experimental.
- 6 one-click profiles: `daily-balanced`, `gaming-max`, `battery-max`, `privacy-hardened`,
  `clean-slate`, `smooth-navigation`.
- 34 curated tools across 6 sections, each labelled `NO-ROOT`, `ADB`, `SHIZUKU` or `ROOT`.
- Every tweak declares a risk level, its access requirement, the OriginOS versions it applies
  to, whether it is verified on hardware, and how it is undone.

**CLI** (`cli/`, stdlib only)

- `doctor`, `devices`, `info`, `catalog`, `show`, `profiles`, `apply`, `revert`, `status`,
  `debloat`, `awesome`, `export` and `lint`.
- `--dry-run` everywhere, an exportable `sh`/`ps1` ADB script, and colour output that respects
  `NO_COLOR`.
- 128 unit tests covering the catalog, the op layer and the engine.

**Engineering**

- One catalog shared by the app and the CLI (Gradle `assets.srcDirs`), linted in CI.
- Documentation generated from the catalog (`scripts/generate_docs.py`), with a CI check that
  fails when it is stale.
- CI: Android build + unit tests + manifest permission guard + tagged releases with APKs;
  catalog lint and CLI tests on Python 3.9, 3.11 and 3.13; CLI wheel build and smoke test.

### Security

- No `INTERNET` permission: the app cannot reach the network.
- Every privileged action goes through Shizuku (`shell`, uid 2000); there is no `su` call and
  no write to a read-only partition.
- The permission set is locked by a CI guard against
  [docs/PERMISSIONS.md](docs/PERMISSIONS.md).
- Experimental, hardware-unverified tweaks are opt-in and labelled in the UI.

[1.0.0]: https://github.com/cameleonnbss/originos-toolkit/releases/tag/v1.0.0
