# Contributing

Thanks for wanting to help. Two things make this project unusual, and both are enforced:

1. **A tweak is data.** Adding one means editing JSON, not writing code.
2. **Nothing may be un-undoable by accident.** A change that cannot be reversed on the device
   must say so explicitly, and CI rejects anything that stays silent.

Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before touching the engine, and
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) before touching each other.

---

## Development setup

```console
$ git clone https://github.com/cameleonnbss/originos-toolkit
$ cd originos-toolkit

# Python CLI — no dependencies, nothing to install
$ make test-cli

# Android app — JDK 17 and Android SDK 35
$ gradle testDebugUnitTest lint assembleDebug
# or: make verify
```

There is no committed Gradle wrapper (it would be a binary blob in the repository); CI
provisions Gradle itself. If you want one locally: `make wrapper`.

---

## Troubleshooting the local build

Two Windows-specific traps, both about paths rather than code:

| Symptom | Cause | Fix |
| --- | --- | --- |
| `ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain`, and every test worker dies | Gradle passes its worker classpath through an `@argfile`, and a non-ASCII character in the Gradle home, the Gradle distribution or the project path gets mangled by the console code page | Put `GRADLE_USER_HOME`, the Gradle distribution and the project on a path that is pure ASCII with no spaces (e.g. `C:\build\originos-toolkit`) |
| `Your project path contains non-ASCII characters` from the Android plugin | Same root cause, caught by AGP instead | Move the project, or silence it with `-Pandroid.overridePathCheck=true` (the build then works, but keep the ASCII path for CI parity) |

Neither is about this project's code, and CI is unaffected (Ubuntu, ASCII paths). If your
build is green locally and red in CI, it is almost always the catalog or the generated docs:
run `make verify`.

## Adding a tweak

Edit `catalog/tweaks.json`. A minimal entry:

```json
{
  "id": "my-tweak",
  "name": "Human-readable name",
  "category": "display",
  "risk": "low",
  "requires": "shizuku",
  "verified": true,
  "originOs": ["originos4", "originos5"],
  "summary": "One sentence, shown in the list.",
  "details": "Everything a careful user needs: what it changes, what breaks, what it costs.",
  "actions": [
    { "op": "settings_put", "ns": "global", "key": "some_key", "value": "1" }
  ]
}
```

### The rules

| Rule | Why |
| --- | --- |
| `id` is kebab-case and unique | It is referenced by profiles, the journal and the docs |
| `summary` and `details` are written like documentation, not marketing | Someone is going to trust this with their phone |
| Every op is in the documented op set | `lint` rejects anything else — see the op table in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| The tweak is reversible, **or** declares `"revert": [...]`, **or** sets `"reversible": false` | `lint` rejects silence |
| `originOs` lists the versions it applies to | Drives the compatibility notes in the app |
| `verified: false` if you have not tested on real hardware | Unverified tweaks are grouped in the opt-in Experimental category |
| No `pm uninstall`, no root, no writes to `/system` | Disabling is reversible; uninstalling loses data; root is a different project |

Prefer the narrowest primitive that works: `settings_put` over `cmd`, `cmd` over `shell`. The
wider the command, the less the engine can verify and undo.

### Then

```console
$ make lint            # catalog validation
$ make docs            # regenerate docs/TWEAKS.md and docs/AWESOME-ORIGINOS.md
$ make test            # Python + Kotlin + lint
```

CI runs all of that, plus a check that the generated documentation is not stale. If you forget
`make docs`, the build tells you.

---

## Adding a curated entry

Edit `catalog/awesome.json`:

```json
{
  "name": "Tool name",
  "repo": "owner/repo",
  "url": "https://github.com/owner/repo",
  "access": "no-root",
  "description": "What it does, in your own words, honestly including the sharp edges.",
  "tags": ["debloat", "shizuku"]
}
```

Access levels are `no-root`, `adb`, `shizuku` and `root`. Be strict about them: telling
someone a rooted tool is "no-root" is the fastest way to waste their evening. Descriptions are
paraphrases — do not paste marketing copy, and do not paste text you do not have the right to
include.

---

## Changing the engine

Applies, probes, inverses and journal behaviour are duplicated on purpose: `cli/originos_toolkit/`
and `app/src/main/kotlin/.../core/` implement the same rules, and the two test suites assert the
same behaviours. If you change one, change the other and update both suites. A pull request that
changes only one of them will be asked about it.

Behaviours that have tests and must keep them:

- the inverse is persisted **before** the first write reaches the device;
- a re-apply of an applied tweak is refused (unless forced), so the original inverse survives;
- a package that was already disabled is not re-enabled on revert;
- a failed probe aborts the apply with nothing written;
- `--dry-run` executes nothing and writes no journal.

---

## Reporting a bad tweak

Open an issue with the device model, `ro.vivo.os.version`, the tweak id, and what happened.
If a tweak is wrong — it does nothing, it breaks something, or its inverse is incomplete —
say so plainly. Demoting a tweak is a success, not a failure: the [issue
template](.github/ISSUE_TEMPLATE/bug_report.yml) has a field for exactly this.

---

## Pull request checklist

- [ ] `make verify` passes (lint, docs freshness, Python tests).
- [ ] `gradle testDebugUnitTest` passes if you touched Kotlin.
- [ ] New tweaks declare how they are undone.
- [ ] New permissions… there are no new permissions without a discussion, an entry in
      [docs/PERMISSIONS.md](docs/PERMISSIONS.md) and an update to the CI guard.
- [ ] The commit message says *why*, not just what.

Commit messages: imperative subject under ~72 characters, a blank line, then the reasoning.
`Add per-app refresh watcher` beats `updated files`.
