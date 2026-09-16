# Architecture

Two implementations of one engine, one source of truth for the data.

```
catalog/*.json  ──┬──►  Android app   (Kotlin, Compose, Shizuku)   app/src/main/kotlin
                  │        └── wired in via Gradle `assets.srcDirs`, shipped inside the APK
                  └──►  CLI           (Python 3, stdlib only)       cli/originos_toolkit
                                                    └──► adb (USB / wireless)
```

The catalog files are not copied anywhere. The Android build adds `catalog/` as an asset
directory, so `assets/tweaks.json` *is* `catalog/tweaks.json`. The Python package discovers
the same directory by walking up from its own location or the working directory.

---

## The op model

A tweak is a list of **ops**. Ops are the only thing the engine knows how to execute, which
is what makes the safety rules enforceable in one place.

| Op | Command it produces | Reversible how |
| --- | --- | --- |
| `settings_get` / `put` / `delete` | `settings <verb> <ns> <key> [value]` | previous value read first, then restored or deleted |
| `device_config_get` / `put` / `delete` | `device_config <verb> <ns> <key> [value]` | same |
| `pm_disable` | `pm disable-user --user 0 <pkg>` | `pm enable --user 0 <pkg>`, **only if it was enabled before** |
| `pm_enable` | `pm enable --user 0 <pkg>` | `pm disable-user` |
| `pm_list` | `pm list packages …` | read-only |
| `overlay_enable` / `disable` | `cmd overlay enable|disable --user 0 <pkg>` | inverse op, **only if the state actually changed** |
| `overlay_list` | `cmd overlay list --user 0` | read-only |
| `cmd` | `cmd <args…>` | **not derivable — an explicit `revert` is required** |
| `shell` | raw shell string | **not derivable — an explicit `revert` or `reversible: false`** |
| `svc` | `svc <service> enable|disable` | flag flipped back |
| `wm_density_delta` | `wm density <current ± n%>` | `wm density <original>`, captured from `wm density` |

`wm_density_delta` deserves a note: it is **relative** on purpose. A hard-coded density would
be wrong on half the panels out there, so the catalog says "-10 %" and the engine computes
the number from the device, floors it at 72 so it can never produce an unusable UI, and
journals the original for an exact restore.

## The three-step apply

```
probe → journal → write
```

1. **probe.** For every op with a `probeFor` target, read the current state and normalise it:
   - `settings get` → the value, or `""` when the key does not exist (`null`);
   - `pm list packages -d <pkg>` → `disabled` / `enabled`;
   - `cmd overlay list` → `enabled` / `disabled` / `absent` (bare package lines are ignored,
     only `[x]`/`[ ]` state lines count);
   - `wm density` → the override if present, otherwise the physical density.
   If a probe fails, the apply is **aborted before anything is written**.
2. **journal.** The inverse is derived and persisted. The derivation is per-op:
   `inverseAction(op, previousState)`; an explicit `revert` array in the catalog wins over
   derivation. Returning `null` means "undoing would be wrong" — an already-disabled
   package, an overlay that was already on, a fire-and-forget command.
3. **write.** Only now do commands reach the device. A failure is reported per step and the
   journal entry is kept, so the revert can still be retried.

If the derivation yields nothing and the catalog did not declare the tweak one-shot, the
engine refuses to apply it at all. That is the last line of defence behind `lint`.

## Status detection

`status <id>` (CLI) and **Verify** (app) re-run the probes and compare against the expected
state (`settings_put` → the target value, `pm_disable` → `disabled`, …). Tweaks with nothing
readable — a one-shot `shell` command — report `null`/`?` rather than pretending.

## Journal format

Identical in both implementations, so a journal written by the CLI is readable in the app's
`SharedPreferences` given the same entries:

```json
{
  "journalVersion": 1,
  "entries": [
    {
      "tweakId": "force-max-refresh-rate",
      "name": "Force maximum refresh rate (120 / 144 / 165 Hz)",
      "appliedAt": "2026-09-16T21:04:11",
      "inverse": [
        { "op": "settings_put", "ns": "system", "key": "peak_refresh_rate", "value": "60.0" },
        { "op": "settings_delete", "ns": "system", "key": "min_refresh_rate" }
      ]
    }
  ]
}
```

- CLI path: `~/.local/state/originos-toolkit/journal.json` (XDG) or
  `%APPDATA%\originos-toolkit\journal.json`.
- App path: `SharedPreferences("journal")`, excluded from cloud backup and device transfer —
  the journal is device-specific state and would be wrong on another phone.

A corrupt journal is quarantined (`.corrupt.json`) rather than crashing anything: being
unable to revert is bad, being unable to launch is worse.

## The Android app

| Layer | Files | Notes |
| --- | --- | --- |
| Model | `core/model/Models.kt`, `CatalogParser.kt` | Parsed with `org.json`, no Kotlin serialisation plugin: fewer moving parts, and the same parser runs in JVM unit tests |
| Ops | `core/ops/Ops.kt` | The Kotlin twin of `ops.py`, including quote-when-needed rendering |
| Shell | `core/shell/` | `ShizukuShell` + its `UserService` helper (uid 2000) and a read-only `LocalShell` fallback, so the dashboard works before Shizuku is up |
| Engine | `core/TweakEngine.kt` | Probe → journal → write, same rules as the CLI |
| Services | `service/` | `PerAppRefreshService` (foreground, polls `UsageStatsManager`), `FpsOverlayService` (overlay + `Choreographer`) |
| UI | `ui/` | Compose, one `ToolkitViewModel` holding a single immutable `ToolkitUiState` |

Design choices worth knowing about:

- **`org.json` instead of kotlinx.serialization.** The parser has to be runnable in JVM unit
  tests against the real catalog files; `org.json` needs no compiler plugin and no generated
  code, which keeps the build reproducible.
- **A hand-written Binder protocol instead of AIDL.** Shizuku does not let an app call
  `newProcess` directly — the supported way to get the shell identity is a *user service*:
  Shizuku launches a component of yours in a process running as uid 2000, and you talk to it.
  That interface could be AIDL, but AIDL is compiled by a native `aidl.exe` that fails on
  Windows accounts or project paths containing non-ASCII characters (`C:\Users\José\...`).
  [`ShellProtocol`](../app/src/main/kotlin/dev/cameleonnbss/originostoolkit/core/shell/ShellProtocol.kt)
  does the same job — descriptor, transaction codes, exception slot — in about eighty lines of
  `Parcel` reads and writes, with no build-tool dependency.
- **The privilege boundary is one file.** Everything the toolkit is allowed to do happens in
  `UserService`, running as the shell user. If you want to audit the app's power, that is the
  file to read, and `adb shell` is the reference for what it can reach.
- **No DI framework.** `AppContainer` is a hand-written object graph. At this size, Hilt
  would be more indirection than the app has classes.
- **The per-app watcher does not journal its writes.** It changes `peak_refresh_rate` dozens
  of times an hour; journalling that would drown the journal. It stores the two original
  values in `Prefs` and puts them back when it stops.
- **The FPS overlay is honest about its measurement.** It counts the vsync callbacks the
  overlay receives — that tracks the panel's real refresh rate, and it is *not* a per-game
  frame counter. Real compositor timings come from the explicit
  `dumpsys SurfaceFlinger --latency` action on the dashboard.
- **No Gradle wrapper jar is committed.** CI provisions Gradle itself
  (`gradle/actions/setup-gradle`), which avoids a binary blob in the repository; a one-line
  command generates a local wrapper if you want one.

## Testing

The invariants are the product, so they are tested from both sides:

| Concern | Python | Kotlin |
| --- | --- | --- |
| Catalog integrity (ids, risk levels, categories, profiles, curated list) | `cli/tests/test_catalog.py` | `CatalogTest.kt` |
| Op translation, probes, inverses | `cli/tests/test_ops.py` | `OpsTest.kt` |
| Apply/revert/status, journal-before-write, dry-run, experimental gate | `cli/tests/test_engine.py` | `TweakEngineTest.kt` |
| Journal persistence and corruption recovery | `cli/tests/test_journal.py` | — |
| Binder payload encoding/decoding | — | `OpsTest.kt` (round trip) |
| Profile expansion | `cli/tests/test_profiles.py` | — |
| CLI surface | `cli/tests/test_cli.py` | — |

Both suites assert the *same* behaviours (for example: "a re-apply is refused so the revert
stays correct", "an already-disabled package is not re-enabled on revert"), which is what
stops the two engines from drifting apart.
