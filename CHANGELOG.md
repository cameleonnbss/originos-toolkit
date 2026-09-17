# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- `export -o <file>` no longer depends on `Path.write_text(newline=...)`, which only exists
  on Python 3.10 and up. The generated script is now written as bytes, so the `LF` of a `.sh`
  export and the `CRLF` of a `.ps1` export survive verbatim on every platform.

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
